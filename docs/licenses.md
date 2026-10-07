# Components and distribution

Original WinBridge code: GPL-3.0-or-later (root LICENSE).

| Component | Source / version | Licence |
| --- | --- | --- |
| Termux:X11 | [fork](https://github.com/tqmane/winbridge-x11), native binary / DEX commit `fa3a8b430e2896a19f44c99a9cb056254615ae06` | GPL-3.0 |
| PRoot | [termux/proot](https://github.com/termux/proot/tree/v5.1.107.96), 5.1.107.96 | GPL-2.0-or-later (program copyright notice) |
| talloc | [upstream source](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz), 2.5.0 | Termux package declares GPL-3.0; retain its full copyright notices |
| libandroid-shmem | [source](https://github.com/termux/libandroid-shmem/tree/v0.7), 0.7 | BSD-3-Clause |
| Soda / Wine | [Bottles runner source](https://github.com/bottlesdevs/wine/tree/soda), 11.0-27 experimental | Wine LGPL-2.1-or-later plus bundled components' notices |
| Ubuntu userland | Ubuntu Base 24.04.5 amd64; additional signed Ubuntu packages | Per-package copyright files in `/usr/share/doc` |

The build fetches hash-pinned native binaries. PRoot's DT_NEEDED string is changed
from `libtalloc.so.2` to `libtalloc.so` solely so Android can extract it as a native
library. The reproducible transformation is in `scripts/fetch_native.py`.

Before publicly distributing an APK with these libraries, publish the complete
corresponding source and build/packaging scripts alongside it, including recursive
X11 submodule sources, the exact Termux package recipes and patches, upstream
tarballs and notices. A link to a moving upstream branch alone is insufficient.
The [native SDK prerelease](https://github.com/tqmane/winbridge-office-android/releases/tag/native-sdk-20261007)
ships the component notices and a corresponding-source archive, including all
X11 submodules and the Termux recipes. The APK build also includes those notices
as `assets/third-party-notices.txt`. No APK release has been published yet. The
source repository does not contain fetched object-code packages.

Office, ODT, Microsoft fonts, account data and activation files are never build
inputs or repository assets. Download Microsoft software only into the user's
runtime. Preserve Microsoft's licensing and authentication behavior.

## Direct input helper

`native/windows-input.c` is project source under GPL-3.0-or-later. The pinned
LLVM-MinGW build uses mingw-w64 revision
`a3d93999ef0521681d45e445c39964a7af0f593f`; its notices and the LLVM runtime
license are retained in `native/windows-input-notices.txt` and copied into the
APK assets by `scripts/build_native_input.py`. No Microsoft binary is used as a
build input or redistributed by this helper.
