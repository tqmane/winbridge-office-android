# Verification ledger

Record the date, exact component version, command, result and remaining boundary
for every milestone. Host build success does not establish Android runtime or
Office compatibility.

| Gate | Result |
| --- | --- |
| Android APK build / install / launch | Passed on dedicated AVD; original companion build also passed lint |
| Android 16 x86_64 / tablet display | Passed: Pixel 9 profile, Android 16 (API 36.1 image), 2560x1600, 240 dpi |
| Linux runtime / native x86_64 Wine | App-owned Ubuntu/glibc smoke passed; Soda 11 version and Wine boot processes confirmed in companion experiment |
| Wine boot / Win32 window / input | Pending |
| Terminate / relaunch | Pending |
| Official Office installer | Pending |
| Word ribbon / document / input | Pending |
| Excel / PowerPoint | Pending |
| URI round trip / task lifecycle | Pending |
| ARM64 | Pending |

Raw logs, downloads and emulator captures belong in ignored `artifacts/` or
`.local/`. Publish only reviewed, sanitised evidence. Never publish account data,
host paths, tokens, document contents or Microsoft binaries.

## 2026-10-07 experiments

- Dedicated AVD: `WinBridge_Pixel9_API36`, serial `emulator-5580`, 32 GB data.
  Existing Pixel_9 belongs to another chat. Physical devices are forbidden.
- Compiled AGP 9.0.1 / Gradle 9.3.0 APK; installed and launched the management UI.
  Android reports 2560x1600 at 240 dpi. Companion X11's usable canvas was
  2560x1403 with its system bars and extra keyboard toolbar visible.
- App-owned runtime smoke: HTTPS download with pinned SHA-256, rootfs extraction,
  PRoot in the APK's native library directory, `/bin/sh`, `uname`, `ldd`, `id` and
  `/etc/os-release` all ran under the app's ordinary Android UID. Reported
  Ubuntu 24.04.5 / glibc 2.39. PRoot's guest `uid=0` is simulated, not Android root.
- Android tar cannot create the rootfs hardlinks directly. Extracting through
  PRoot's link2symlink extension with the *guest root set to the extraction root*
  handles hardlinks and absolute Linux symlinks correctly. Extracting against the
  Android host `/` produced invalid alternatives and was rejected.
- Docker/OCI download through the current PRoot-Distro Python TLS client failed.
  Switched to Canonical's pinned Ubuntu Base archive downloaded with curl.
  Ubuntu package downloads had HTTP pipeline hash mismatches; disabling APT
  pipeline depth and retrying preserved hash verification and completed downloads.
- Soda 11.0-27 includes an i386 Unix loader: the amd64 rootfs alone is insufficient.
  Added i386 libc and then i386 graphics libraries as errors identified them.
- PRoot 5.1.107.96 read `socketcall`'s three 32-bit arguments as host `unsigned long`
  values. A freestanding i386 SCM_RIGHTS/SCM_CREDENTIALS test fails with exit 11 on
  upstream and exits 0 on the patched 64-bit tracer. Wine moved past the same
  `sendmsg: Bad address` failure. Source and test are in the PRoot fork.
- Wine tests launched through `run-as` hit executable mapping failures. Repeat
  through the real Android app/service context before drawing runtime conclusions;
  the same failure was absent when launched by the app's RUN_COMMAND button.

No Win32 application window, Office installation or Office editor is verified yet.
