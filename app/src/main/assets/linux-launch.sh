#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
export PATH="/opt/soda/bin:/usr/bin:/bin"
export WINEPREFIX=/winbridge/prefix WINEARCH=win64 DISPLAY=:1
export XAUTHORITY=/winbridge/.Xauthority
export WINEDEBUG=-all,err+all WINEDLLOVERRIDES='mscoree,mshtml='
export LIBGL_ALWAYS_SOFTWARE=1 WINEESYNC=0 WINEFSYNC=0
action=${1:-status}
case "$action" in
    status) wine --version; test ! -f "$WINEPREFIX/.winbridge-initialized" || echo 'Shared prefix initialized'; exit ;;
    stop) wineserver -k; echo 'All Wine processes stopped.'; exit ;;
    reset)
        wineserver -k || true
        wineserver -w || true
        if [[ -d "$WINEPREFIX" ]]; then
            backup=$(mktemp -d /winbridge/prefix-backup.XXXXXXXX)
            mv "$WINEPREFIX" "$backup/prefix"
            echo "Old prefix retained in $backup/prefix"
        fi
        exit ;;
    notepad|winecfg|word|excel|powerpoint|install-office) ;;
    *) echo 'Unsupported runtime action' >&2; exit 2 ;;
esac
[[ -f /winbridge/.runtime-ready ]] || { echo 'Initialize the Wine runtime first.' >&2; exit 1; }
exec 9>/winbridge/prefix-start.lock
flock 9
wm=$(xprop -root _NET_SUPPORTING_WM_CHECK | sed -n 's/.*# \(0x[0-9a-f]*\).*/\1/p')
if [[ -z "$wm" ]] || ! xprop -id "$wm" _NET_SUPPORTING_WM_CHECK >/dev/null 2>&1; then
    openbox >/winbridge/logs/openbox.log 2>&1 &
fi
if [[ ! -f "$WINEPREFIX/.winbridge-initialized" ]]; then
    wineboot --init
    touch "$WINEPREFIX/.winbridge-initialized"
fi
flock -u 9
wine /winbridge/windows-input.exe >>/winbridge/logs/direct-input.log 2>&1 &
case "$action" in
    notepad) wine notepad.exe ;;
    winecfg) wine winecfg ;;
    install-office)
        mkdir -p /winbridge/office
        cd /winbridge/office
        odt=officedeploymenttool_20326-20112.exe
        if [[ ! -f "$odt" ]]; then
            curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
                'https://download.microsoft.com/download/6c1eeb25-cf8b-41d9-8d0d-cc1dbc032140/officedeploymenttool_20326-20112.exe' \
                -o "$odt.part"
            mv "$odt.part" "$odt"
        fi
        echo "fbb64358fd4168acd52ee4efe47ffd032b6231dfb415ae2dce61b0e58ba67f86  $odt" | sha256sum -c -
        wine "$odt" /quiet '/extract:Z:\winbridge\office'
        if [[ ! -f configuration.xml ]]; then
            cat >configuration.xml <<'XML'
<Configuration>
  <Add OfficeClientEdition="64" Channel="Current" SourcePath="Z:\winbridge\office">
    <Product ID="O365ProPlusRetail">
      <Language ID="en-us" />
      <ExcludeApp ID="Access" /><ExcludeApp ID="Outlook" /><ExcludeApp ID="OneNote" />
      <ExcludeApp ID="Publisher" /><ExcludeApp ID="Lync" /><ExcludeApp ID="Groove" />
      <ExcludeApp ID="Teams" /><ExcludeApp ID="OneDrive" />
    </Product>
  </Add>
  <Display Level="Full" AcceptEULA="FALSE" />
</Configuration>
XML
        fi
        wine setup.exe /download 'Z:\winbridge\office\configuration.xml'
        wine setup.exe /configure 'Z:\winbridge\office\configuration.xml'
        ;;
    word|excel|powerpoint)
        case "$action" in word) exe=WINWORD.EXE;; excel) exe=EXCEL.EXE;; powerpoint) exe=POWERPNT.EXE;; esac
        # Only installed applications: Click-to-Run also stages incomplete EXEs under Updates/.
        app="$WINEPREFIX/drive_c/Program Files/Microsoft Office/root/Office16/$exe"
        [[ -f "$app" ]] || { echo "Microsoft Office is not installed: $exe missing." >&2; exit 1; }
        wine "$app" ;;
esac
# Keep the foreground job alive while any application uses the shared wineserver.
wineserver -w
