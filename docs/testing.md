# Verification ledger

Record the date, exact component version, command, result and remaining boundary
for every milestone. Host build success does not establish Android runtime or
Office compatibility.

| Gate | Result |
| --- | --- |
| Android APK build / install / launch | Passed on dedicated AVD; standalone build and lint passed |
| Android 16 x86_64 / tablet display | Passed: Pixel 9 profile, Android 16 (API 36.1 image), 2560x1600, 240 dpi |
| Linux runtime / native x86_64 Wine | Passed inside standalone APK with SDK 28 execution profile |
| Wine boot / Win32 window / input | Notepad GUI, Android keyboard, Ctrl+S/Enter and saved-byte round trip passed; full pointer/stylus tests pending |
| Terminate / relaunch | Automated stop/relaunch passed; measured Notepad reappearance 32.4 s |
| Official Office installer | ODT verified, extracted and setup.exe /download running; Office installation itself pending |
| Word ribbon / document / input | Pending |
| Excel / PowerPoint | Pending |
| URI round trip / task lifecycle | Pending |
| ARM64 | Pending |
| Native Android input without X11 | Upstream driver inspected; not implemented or tested |

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

The companion experiment displayed Wine Notepad with its menus, text and editing
area after installing both guest font architectures and creating a fresh prefix.
Linux `xmessage` also rendered correctly. Input validation and the standalone
Win32 GUI gate remain in progress; Office is not installed or verified.

The standalone APK has now installed the complete guest Wine runtime in its own
storage, without using Termux. Both companion packages are disabled for subsequent
tests. A first embedded-display run exposed a Java/native ABI mismatch: the
nightly release's `target_commitish` was not the source commit of its uploaded
APK. The APK's DEX identifies `fa3a8b430e2896a19f44c99a9cb056254615ae06`; align the
Java source, native library and recursively pinned C dependencies to that commit.
Do not add dummy JNI methods to hide a version mismatch.

## Standalone smoke result

Both `com.termux` and `com.termux.x11` were disabled. The foreground display and
Linux/Wine processes belonged to WinBridge. With target SDK 28's execution
profile, Wine Notepad rendered and `scripts/check_notepad.py` passed: Android
keyboard text, Android Ctrl+S, an automated save path, Android Enter, exact saved
bytes and rejection of an X client without the private cookie. The full
`scripts/check_lifecycle.py` then terminated Wine, relaunched Notepad in 32.4 s
and repeated that input/save check successfully. Screenshot:
`artifacts/notepad-input-pass.png` (kept local).

The same APK code with target SDK 36 runs the Linux shell and installer setup
scripts but fails Wine PE executable mappings (`ntdll.dll` protection / noexec
error). SDK 28 changes the app's execution compatibility domain; SELinux and the
Android system were not disabled or rooted. The project currently defaults to
the working compatibility profile and retains the modern-target comparison flag.

Stop Wine initially left traced clients alive after `wineserver -k`. The runtime
now owns and terminates its PRoot sessions as well. The lifecycle check guards
this regression. Initial prefix creation still takes several minutes.

ODT 16.0.20326.20112 ran in the standalone prefix and began downloading Office
64-bit build 16.0.20430.20146 from Microsoft. Payload growth was observed; that is
not yet an installation, Word, activation or editing success claim.

## Direct input and runtime cost

The opt-in direct Windows input backend built and passed lint, its host
cryptographic/parser self-test, and `scripts/check_direct_input.py` on Android.
The test rejected an invalid MAC and a replayed sequence, then used Android
typing/Ctrl+S/pathname/Enter to save exact text in Notepad. The Wine helper's
matching session counted the injected Win32 events. Companion packages remained
disabled. Pointer/right-click/wheel/IME/pen validation is still outstanding.

Office `/download` completed. The subsequent setup spent 31m 6s validating
10,480 tasks, then extracted 2.2 GB into the staging directory. The user found
the runtime too heavy, so Wine/Office were stopped with the download cache
preserved. No completed Office installation or editor is claimed.

The dedicated AVD was restarted with 2 vCPUs, 3072 MB guest RAM and `-gpu host`;
logs confirmed hardware graphics. `-gpu auto` had still selected software
rendering, so it was not sufficient. The tablet resolution/density stayed
2560x1600/240. The Gradle heap ceiling is now 768 MB with one worker. Other AVDs
and physical devices were not modified.

The incomplete Click-to-Run service automatically restarted with Wine Notepad.
For isolated input tests only, its `ClickToRunSvc` Start value was changed from
2 (automatic) to 3 (manual) while Wine was stopped; the original registry is kept
in ignored local artifacts. This is not a shipped Office compatibility tweak.
After isolation, a 3-second idle emulator sample used 0.06 CPU-seconds/second;
that is not an Office installation speed comparison. Fixing the Wine/PRoot
execution cost remains a separate performance gate before another full install.

The two-session lifecycle test subsequently exposed a real shutdown bug:
PRoot ignored SIGTERM, while Android's `Process.destroyForcibly()` inherited the
default implementation that repeats `destroy()`. The PRoot fork now handles
SIGTERM by killing and reaping its tracees. The app verifies process exit before
reporting success and reports termination failures without moving the prefix.
`check_lifecycle.py` now opens two Notepad sessions before stopping them.

The updated test passed full stop, relaunch in 10.9 s, Android typing, Ctrl+S,
pathname, Enter and exact saved contents with direct input enabled. Normal Wine
launches no longer use PRoot's simulated-root extension; package installation
still does. `USER=root` preserves the existing Windows profile name independently
of the actual Linux UID. This startup sample used the newer AVD configuration
and is not a controlled measurement of that single change or of Office speed.
