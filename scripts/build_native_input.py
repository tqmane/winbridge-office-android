"""Build our Win32 input helper with MinGW (no Microsoft binaries, no Termux).

Windows defaults to a pinned, checksum-verified LLVM-MinGW toolchain in .local.
Other hosts can pass --cc x86_64-w64-mingw32-gcc.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--cc")
args = parser.parse_args()
compiler = args.cc
if not compiler:
    if os.name != "nt":
        parser.error("Pass --cc x86_64-w64-mingw32-gcc on this host")
    name = "llvm-mingw-20261006-ucrt-x86_64"
    compiler = root / ".local/toolchains" / name / "bin/x86_64-w64-mingw32-clang.exe"
    if not compiler.is_file():
        archive = root / ".local/downloads" / (name + ".zip")
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.is_file():
            urllib.request.urlretrieve("https://github.com/mstorsjo/llvm-mingw/releases/download/20261006/" + archive.name, archive)
        with archive.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != "317492c456aa27ee607a5919f1d2d38dcdc1112516a24d0bf4b00d078f52d17a":
                raise SystemExit("LLVM-MinGW archive checksum mismatch")
        with zipfile.ZipFile(archive) as package:
            package.extractall(root / ".local/toolchains")

output = root / ".local/input-assets/windows-input.exe"
output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([str(compiler), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-static", "-s",
                str(root / "native/windows-input.c"), "-lws2_32", "-lbcrypt", "-luser32", "-o", str(output)], check=True)
if os.name == "nt":
    # Self-test does not listen on a socket or inject any input into the host.
    subprocess.run([str(output), "--self-test"], check=True)
shutil.copyfile(root / "native/windows-input-notices.txt", output.with_name("windows-input-notices.txt"))
print(output)
