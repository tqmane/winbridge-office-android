"""Fetch pinned upstream Android runtime binaries; nothing proprietary is bundled.

Native outputs are ignored build inputs. See docs/licenses.md before distributing
an APK. Run --self-test for the archive and ELF packaging regression check.
"""
import hashlib
import io
from pathlib import Path
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "data/data/com.termux/files/usr/"
PACKAGES = {
    "proot": ("p/proot/proot_5.1.107.96_x86_64.deb", "77ea45540071ca543adda2b51aca2bc3761c52904d013fd0890682288eff9455"),
    "talloc": ("libt/libtalloc/libtalloc_2.5.0_x86_64.deb", "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530"),
    "shmem": ("liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb", "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"),
}


def fetch(url, digest, name):
    destination = ROOT / ".local/downloads" / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == digest:
        return destination
    partial = destination.with_suffix(destination.suffix + ".part")
    urllib.request.urlretrieve(url, partial)
    if hashlib.sha256(partial.read_bytes()).hexdigest() != digest:
        raise ValueError(f"SHA-256 mismatch: {name}; refusing to package it")
    partial.replace(destination)
    return destination


def ar_members(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("Not a Debian ar archive")
    pos = 8
    while pos < len(data):
        header = data[pos:pos + 60]
        if len(header) != 60 or header[58:] != b"`\n":
            raise ValueError("Invalid ar header")
        size = int(header[48:58])
        if size < 0 or pos + 60 + size > len(data):
            raise ValueError("Truncated ar member")
        yield header[:16].decode("ascii").strip().rstrip("/"), data[pos + 60:pos + 60 + size]
        pos += 60 + size + size % 2


def deb_files(path):
    for name, payload in ar_members(path.read_bytes()):
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
                return {m.name.removeprefix("./"): archive.extractfile(m).read()
                        for m in archive if m.isfile()}
    raise ValueError("Debian archive has no payload")


def rename_talloc_needed(elf):
    # Android extracts only lib*.so. Keep the ELF string table offsets unchanged.
    old = b"libtalloc.so.2\0"
    if not elf.startswith(b"\x7fELF") or elf.count(old) != 1:
        raise ValueError("Unexpected PRoot ELF dependency; inspect the new build")
    return elf.replace(old, b"libtalloc.so\0\0\0")


def main():
    if sys.argv[1:] == ["--self-test"]:
        original = b"\x7fELFprefixlibtalloc.so.2\0suffix"
        patched = rename_talloc_needed(original)
        assert len(patched) == len(original) and b"libtalloc.so\0" in patched
        for invalid in (b"", b"!<arch>\nshort"):
            try:
                list(ar_members(invalid))
            except ValueError:
                pass
            else:
                raise AssertionError("Invalid archive accepted")
        print("native packaging checks passed")
        return
    output = ROOT / ".local/native/x86_64"
    output.mkdir(parents=True, exist_ok=True)
    packages = {key: deb_files(fetch("https://packages.termux.dev/apt/termux-main/pool/main/" + path,
                                    digest, path.split("/")[-1]))
                for key, (path, digest) in PACKAGES.items()}
    (output / "libproot.so").write_bytes(rename_talloc_needed(packages["proot"][PREFIX + "bin/proot"]))
    (output / "libproot-loader.so").write_bytes(packages["proot"][PREFIX + "libexec/proot/loader"])
    (output / "libproot-loader32.so").write_bytes(packages["proot"][PREFIX + "libexec/proot/loader32"])
    talloc = [data for path, data in packages["talloc"].items() if path.startswith(PREFIX + "lib/libtalloc.so.")]
    if len(talloc) != 1:
        raise ValueError("Expected one concrete libtalloc shared object")
    (output / "libtalloc.so").write_bytes(talloc[0])
    (output / "libandroid-shmem.so").write_bytes(packages["shmem"][PREFIX + "lib/libandroid-shmem.so"])
    apk = fetch("https://github.com/termux/termux-x11/releases/download/nightly/termux-x11-universal-debug.apk",
                "64995746d1887a0e70bc8977d4a9c5ea56e3e00eadd4a6eeab32ae6048ba733a",
                "termux-x11-universal-debug.apk")
    with zipfile.ZipFile(apk) as archive:
        (output / "libXlorie.so").write_bytes(archive.read("lib/x86_64/libXlorie.so"))
    print("Verified x86_64 PRoot, loader, talloc, shmem and X11 libraries are ready.")


if __name__ == "__main__":
    main()
