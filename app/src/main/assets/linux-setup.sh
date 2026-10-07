#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
mkdir -p /winbridge/logs
printf 'Acquire::http::Pipeline-Depth "0";\nAcquire::https::Pipeline-Depth "0";\nAcquire::Retries "3";\nAcquire::Languages "none";\n' >/etc/apt/apt.conf.d/99winbridge-network
dpkg --add-architecture i386
apt-get update
apt-get install -y --no-install-recommends ca-certificates
sed -i 's,http://,https://,g' /etc/apt/sources.list.d/ubuntu.sources
apt-get install -y --no-install-recommends curl xz-utils \
    libc6:i386 libgcc-s1:i386 libunwind8:i386 \
    libfreetype6 libfontconfig1 libx11-6 libxext6 libxrender1 libxrandr2 libxi6 \
    libxcursor1 libxfixes3 libxcomposite1 libxinerama1 libxxf86vm1 libgnutls30t64 \
    libfreetype6:i386 libfontconfig1:i386 libx11-6:i386 libxext6:i386 libxrender1:i386 \
    libxrandr2:i386 libxi6:i386 libxcursor1:i386 libxfixes3:i386 libxcomposite1:i386 \
    libxinerama1:i386 libxxf86vm1:i386 libgnutls30t64:i386 \
    libasound2t64 libpulse0 libgl1 libgl1-mesa-dri libglu1-mesa libvulkan1 \
    libdbus-1-3 libudev1 libunwind8 libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
    fonts-liberation fonts-dejavu-core openbox x11-utils x11-xserver-utils xdotool xkb-data
if [[ ! -s /etc/machine-id ]]; then tr -d '-' </proc/sys/kernel/random/uuid >/etc/machine-id; fi
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
touch /winbridge/.runtime-ready
echo 'App-owned Wine runtime installed.'
