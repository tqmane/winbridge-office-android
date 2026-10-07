#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Archived companion experiment. Not shipped in or used by the standalone APK.
set -euo pipefail
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:/system/bin"
export TMPDIR="$PREFIX/tmp"
work="$HOME/.local/share/winbridge"
mkdir -p "$work/logs"
if [[ -x "$work/native/proot" ]]; then export PD_PROOT_BIN="$work/native/proot"; fi
action=${1:-status}
case "$action" in
    status|init|notepad|winecfg|word|excel|powerpoint|install-office|stop|reset|logs) ;;
    *) echo 'Unknown runtime action' >&2; exit 2 ;;
esac

container() { proot-distro login winbridge --shared-tmp --bind "$work:/winbridge" -- "$@"; }

if [[ "$action" == init ]]; then
    exec > >(tee -a "$work/logs/init.log") 2>&1
    exec 9>"$work/init.lock"
    flock -n 9 || { echo 'Runtime initialization already running.'; exit 1; }
    [[ $(uname -m) == x86_64 ]] || { echo 'This PoC currently requires x86_64 Android.'; exit 1; }
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y proot-distro x11-repo
    apt-get update
    apt-get install -y termux-x11-nightly
    if ! container /bin/true 2>/dev/null; then
        rootfs="$work/ubuntu-base-24.04.5-base-amd64.tar.gz"
        [[ -f "$rootfs" ]] || curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
            https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz \
            -o "$rootfs.part"
        [[ ! -f "$rootfs.part" ]] || mv "$rootfs.part" "$rootfs"
        echo "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86  $rootfs" | sha256sum -c -
        proot-distro install "$rootfs" --name winbridge
    fi
    container /bin/bash -s <<'LINUX'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
printf 'Acquire::http::Pipeline-Depth "0";\nAcquire::Retries "3";\n' >/etc/apt/apt.conf.d/99winbridge-network
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
    libfreetype6 libfontconfig1 libx11-6 libxext6 libxrender1 libxrandr2 libxi6 \
    libxcursor1 libxfixes3 libxcomposite1 libxinerama1 libxxf86vm1 libgnutls30t64 \
    libasound2t64 libpulse0 libgl1 libgl1-mesa-dri libglu1-mesa libvulkan1 \
    libdbus-1-3 libudev1 libunwind8 libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
    fonts-liberation fonts-dejavu-core openbox x11-utils x11-xserver-utils xdotool
cd /winbridge
archive=soda-11.0-27-experimental-x86_64.tar.xz
if [[ ! -x /opt/soda/bin/wine ]]; then
    [[ -f "$archive" ]] || curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
        "https://github.com/bottlesdevs/wine/releases/download/soda-11.0-27-experimental/$archive" -o "$archive.part"
    [[ ! -f "$archive.part" ]] || mv "$archive.part" "$archive"
    echo "caa4d6251228a1e1dc915b2f9444f5e7f50c8a68805e51f48af0749dc4e3df07  $archive" | sha256sum -c -
    mkdir -p /opt/soda
    tar -xJf "$archive" --strip-components=1 -C /opt/soda
fi
/opt/soda/bin/wine --version
LINUX
    echo 'Linux runtime installed. Launch Wine Notepad to initialize the shared prefix.'
    exit 0
fi

if [[ "$action" == status ]]; then
    printf 'Android: '; getprop ro.build.version.release
    printf 'Architecture: '; uname -m
    printf 'PRoot: '; proot --version 2>/dev/null | head -n 1 || true
    if command -v proot-distro >/dev/null; then
        container /bin/sh -c '/opt/soda/bin/wine --version; df -h /winbridge; test ! -f /winbridge/prefix/system.reg || echo "Shared prefix exists"'
    else echo 'Runtime is not initialized.'; fi
    exit 0
fi
if [[ "$action" == logs ]]; then
    for log in "$work/logs/"*.log; do
        [[ -f "$log" ]] || continue
        printf '\n--- %s ---\n' "${log##*/}"
        tail -n 60 "$log"
    done
    exit 0
fi
command -v proot-distro >/dev/null || { echo 'Initialize the runtime first.'; exit 1; }

if [[ "$action" != stop && "$action" != reset ]]; then
    if ! pgrep -f 'com.termux.x11.CmdEntryPoint :1' >/dev/null; then
        termux-x11 :1 -ac -legacy-drawing >"$work/logs/x11.log" 2>&1 &
        for attempt in $(seq 1 50); do
            [[ -S "$TMPDIR/.X11-unix/X1" ]] && break
            sleep 0.1
        done
    fi
fi

container /bin/bash -s -- "$action" <<'LINUX'
set -euo pipefail
export PATH="/opt/soda/bin:$PATH"
export WINEPREFIX=/winbridge/prefix
export WINEARCH=win64
export DISPLAY=:1
export WINEDEBUG=-all,err+all
export WINEDLLOVERRIDES='mscoree,mshtml='
export LIBGL_ALWAYS_SOFTWARE=1
export WINEESYNC=0 WINEFSYNC=0
action=$1
if [[ "$action" == stop ]]; then wineserver -k; echo 'Wine stopped.'; exit; fi
if [[ "$action" == reset ]]; then
    wineserver -k || true
    wineserver -w || true
    [[ ! -d "$WINEPREFIX" ]] || mv "$WINEPREFIX" "$WINEPREFIX.backup.$(date +%s)"
    echo 'Old prefix archived. Launch Wine Notepad to create a new prefix.'
    exit
fi
exec >>"/winbridge/logs/$action.log" 2>&1
date -Is
wine --version
if ! pgrep -x openbox >/dev/null; then openbox >/winbridge/logs/openbox.log 2>&1 & fi
if [[ ! -f "$WINEPREFIX/.winbridge-initialized" ]]; then
    wineboot --init
    wineserver -w
    touch "$WINEPREFIX/.winbridge-initialized"
fi
case "$action" in
    notepad) wine notepad.exe ;;
    winecfg) wine winecfg ;;
    word|excel|powerpoint)
        case "$action" in word) exe=WINWORD.EXE;; excel) exe=EXCEL.EXE;; powerpoint) exe=POWERPNT.EXE;; esac
        app=$(find "$WINEPREFIX/drive_c/Program Files" -type f -iname "$exe" -print -quit)
        [[ -n "$app" ]] || { echo "Office is not installed: $exe missing."; exit 1; }
        wine "$app" ;;
    install-office)
        echo 'Office setup is not yet implemented; Wine GUI verification comes first.'
        exit 1 ;;
esac
LINUX
