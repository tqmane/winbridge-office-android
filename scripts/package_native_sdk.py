"""Package only OSS native build inputs and their corresponding source, never Office."""
import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile
from fetch_native import fetch

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts/native-sdk"
OUT.mkdir(parents=True, exist_ok=True)
NAMES = ["libproot.so", "libproot-loader.so", "libproot-loader32.so", "libtalloc.so", "libandroid-shmem.so", "libXlorie.so"]
NOTICE_PREFIXES = ("COPYING", "LICENSE", "COPYRIGHT", "NOTICE", "AUTHORS")


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def add_git(output, repo, prefix, paths=()):
    archive = git(repo, "archive", "HEAD", *paths)
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source:
            original_name = member.name
            member = copy.copy(member)
            member.name = prefix + "/" + original_name
            member.pax_headers = {key: value for key, value in member.pax_headers.items() if key not in ("path", "linkpath")}
            if member.islnk():
                member.linkname = prefix + "/" + member.linkname
            output.addfile(member, source.extractfile(original_name) if member.isfile() else None)


def main():
    binary = ROOT / ".local/native/x86_64/libproot.so"
    assert b"5.1.107.96-winbridge.2" in binary.read_bytes()
    sources = {
        "project": git(ROOT, "rev-parse", "HEAD").decode().strip(),
        "proot": git(ROOT / "third_party/proot", "rev-parse", "HEAD").decode().strip(),
        "x11_host": git(ROOT / "third_party/termux-x11", "rev-parse", "HEAD").decode().strip(),
        "x11_native": "fa3a8b430e2896a19f44c99a9cb056254615ae06",
        "termux_recipes": git(ROOT / "upstream/termux-packages", "rev-parse", "HEAD").decode().strip(),
    }
    metadata = {"sources": sources, "sha256": {name: hashlib.sha256((binary.parent / name).read_bytes()).hexdigest() for name in NAMES}}
    metadata_bytes = (json.dumps(metadata, indent=2) + "\n").encode()
    (OUT / "manifest.json").write_bytes(metadata_bytes)
    talloc = fetch("https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz",
                   "912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007", "talloc-2.5.0.tar.gz")
    shmem = fetch("https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz",
                  "1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867", "libandroid-shmem-0.7.tar.gz")
    x11 = ROOT / "third_party/termux-x11"
    submodules = []
    for line in git(x11, "submodule", "status", "--recursive").decode().splitlines():
        if not line.startswith(" "):
            raise RuntimeError("X11 submodule source is not at its pinned revision: " + line)
        submodules.append(line.split()[1])
    with zipfile.ZipFile(OUT / "winbridge-native-x86_64.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name in NAMES:
            archive.write(binary.parent / name, "x86_64/" + name)
        archive.write(ROOT / "LICENSE", "LICENSE")
        archive.write(ROOT / "docs/licenses.md", "COMPONENTS.md")
        archive.writestr("manifest.json", metadata_bytes)
        for repo in (ROOT / "third_party/proot", x11, *(x11 / path for path in submodules)):
            for path in repo.iterdir():
                if path.is_file() and path.name.upper().startswith(NOTICE_PREFIXES):
                    archive.write(path, "licenses/" + str(path.relative_to(ROOT)).replace("\\", "/"))
        for dependency in (talloc, shmem):
            with tarfile.open(dependency) as source:
                for member in source:
                    if member.isfile() and Path(member.name).name.upper().startswith(NOTICE_PREFIXES):
                        archive.writestr("licenses/" + member.name, source.extractfile(member).read())
    with tarfile.open(OUT / "winbridge-native-sources.tar.gz", "w:gz") as archive:
        add_git(archive, ROOT, "winbridge-office-android")
        add_git(archive, ROOT / "third_party/proot", "winbridge-office-android/third_party/proot")
        add_git(archive, x11, "winbridge-office-android/third_party/termux-x11")
        for path in submodules:
            add_git(archive, x11 / path, "winbridge-office-android/third_party/termux-x11/" + path)
        recipes = ROOT / "upstream/termux-packages"
        root_files = [p.name for p in recipes.iterdir() if p.is_file()]
        add_git(archive, recipes, "termux-packages", (*root_files, "scripts", "packages/proot", "packages/libtalloc", "packages/libandroid-shmem"))
        archive.add(talloc, "dependencies/" + talloc.name)
        archive.add(shmem, "dependencies/" + shmem.name)
        archive.add(OUT / "manifest.json", "manifest.json")
    for name in ("winbridge-native-x86_64.zip", "winbridge-native-sources.tar.gz"):
        path = OUT / name
        print(name, path.stat().st_size, hashlib.sha256(path.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
