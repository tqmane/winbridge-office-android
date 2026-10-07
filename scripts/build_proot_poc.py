"""Build and regression-test the patched PRoot in the dedicated development AVD.

Termux is a build tool here; the resulting APK hosts the binary itself.
"""
from pathlib import Path
import subprocess
import avd

avd.verify_target()
root = Path(__file__).resolve().parents[1]
archive = subprocess.check_output(["git", "-C", str(root / "third_party/proot"), "archive", "HEAD"])
prefix = "/data/data/com.termux/files/usr"
home = "/data/data/com.termux/files/home"
environment = f"export PREFIX={prefix} HOME={home} PATH={prefix}/bin:/system/bin TMPDIR={prefix}/tmp; "
source_tar = root / ".local/proot-source.tar"
source_tar.write_bytes(archive)
avd.adb("push", str(source_tar), "/data/local/tmp/winbridge-proot.tar")
avd.shell("run-as", "com.termux", "sh", "-c", environment +
          'mkdir -p "$HOME/winbridge-proot"; tar -xf /data/local/tmp/winbridge-proot.tar -C "$HOME/winbridge-proot"')
script = r'''
set -eu
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y clang make proot libtalloc libandroid-shmem
cd "$HOME/winbridge-proot"
export CPPFLAGS='-DARG_MAX=131072 -DVERSION=\"5.1.107.96-winbridge\"'
make -C src -j2 CC=clang PROOT_WITH_LIBANDROID_SHMEM=true \
    PROOT_UNBUNDLE_LOADER="$PREFIX/libexec/proot"
clang --target=i686-linux-android24 -O2 -mstackrealign -ffreestanding -fno-stack-protector -fno-pie \
    -nostdlib -static -Wl,-e,_start tests/test-socketcall32.c -o tests/test-socketcall32
before=0
"$PREFIX/bin/proot" -0 ./tests/test-socketcall32 || before=$?
after=0
./src/proot -0 ./tests/test-socketcall32 || after=$?
printf 'socketcall32: upstream exit=%s, patched exit=%s\n' "$before" "$after"
test "$before" = 11
test "$after" = 0
mkdir -p "$HOME/.local/share/winbridge/native"
cp src/proot "$HOME/.local/share/winbridge/native/proot.new"
mv "$HOME/.local/share/winbridge/native/proot.new" "$HOME/.local/share/winbridge/native/proot"
'''
avd.shell("run-as", "com.termux", "sh", "-c", environment + "exec bash -s", input=script.encode())
from fetch_native import rename_talloc_needed
binary = avd.adb("exec-out", "run-as", "com.termux", "cat", home + "/winbridge-proot/src/proot", capture_output=True).stdout
(root / ".local/native/x86_64/libproot.so").write_bytes(rename_talloc_needed(binary))
