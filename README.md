# WinBridge Office for Android

Experimental, tablet-first launchers for Windows Word, Excel and PowerPoint on
Android, using one shared Wine prefix. This is an independent project, not a
Microsoft, Wine, Bottles or Termux product.

## Status

The x86_64 runtime PoC now runs Wine Notepad inside a standalone APK, with both
Termux companion packages disabled. On a dedicated Android 16 Pixel 9 AVD at
2560x1600 / 240 dpi, automated checks passed Android typing, Ctrl+S, Enter, saved
file contents, X11 authentication, stopping Wine and relaunching Notepad.

**Word, Excel and PowerPoint are not verified yet.** The official Office
Deployment Tool has been downloaded, verified, extracted and launched for the
next experiment. ARM64, document intents and complete lifecycle/input support
remain in development. See [the evidence ledger](docs/testing.md).

The APK currently embeds an X11 display component; no separate X11 app is
required. The preferred final native Android input path is a separate milestone:
see [native-input investigation](docs/native-input.md).

## Requirements and limits

- Initial test ABI: x86_64. ARM64 is not implemented yet.
- Android 13+ minimum in the manifest; only Android 16 has been tested.
- Tablet-class landscape display; the test uses 2560x1600 at 240 dpi.
- Allow roughly 20 GB for the Linux userland, Wine prefix and Office payload.
- This PoC uses target SDK 28's execution compatibility for Wine PE mappings.
  The modern SDK 36 build runs Linux but fails Wine's DLL memory-protection step.
  This is an explicit limitation, not a Play Store-ready application.
- A cold prefix initialization is slow. One measured Notepad relaunch after a
  full Wine stop took 32.4 seconds. Performance work remains.

## Scope

- Reuse upstream runtime and display implementations before writing new ones.
- Verify Wine boot, a Win32 GUI, input and relaunch before testing Office.
- Share the runtime and prefix across three Android launcher entries.
- Obtain Office from Microsoft on the user's device. A valid Office licence is
  required; activation remains Microsoft's normal process.
- Never distribute Microsoft installers, Office binaries, branding or licences.

## Build

Use JDK 17, Android SDK 36, Python 3, Git and the Gradle wrapper. Check out
`feat/x86-runtime-poc` while development is in progress.

```powershell
git submodule update --init third_party/proot third_party/termux-x11
python scripts/fetch_native.py --self-test
python scripts/fetch_native.py
python scripts/build_proot_poc.py
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
./gradlew.bat assembleDebug lintDebug --no-daemon --max-workers=1
```

The current PRoot compile helper uses a bootstrapped GitHub debug build of
Termux in the **dedicated development AVD** as its C build host. It builds our
fork, proves the upstream i386 IPC failure and patched success, then copies the
patched executable into the ignored native build inputs. Termux is not an APK
runtime dependency. The build rejects the unpatched PRoot executable.

Output: `app/build/outputs/apk/debug/app-debug.apk`. No Office/ODT binary is in it.
`-PexecutionTargetSdk=36` selects the modern-target comparison build, which is
currently unsuitable for Wine execution.

## Emulator test

`scripts/Start-TabletEmulator.ps1` creates a dedicated Pixel 9 profile AVD using
`system-images;android-36.1;google_apis_playstore;x86_64`, with 32 GB data stored
under ignored workspace files. It uses `emulator-5580` and applies:

```powershell
adb -s emulator-5580 shell wm size 2560x1600
adb -s emulator-5580 shell wm density 240
adb -s emulator-5580 shell settings put system accelerometer_rotation 0
adb -s emulator-5580 shell settings put system user_rotation 0
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5580 shell am start -n io.github.tqmane.winbridge/.MainActivity
```

Initialize the runtime from the app, then launch Wine Notepad. For the standalone
check, disable the two development companions on this AVD and run:

```powershell
adb -s emulator-5580 shell pm disable-user --user 0 com.termux
adb -s emulator-5580 shell pm disable-user --user 0 com.termux.x11
python scripts/check_notepad.py
python scripts/check_lifecycle.py
```

The first check expects a blank Notepad. The lifecycle check stops all Wine
applications and relaunches a blank Notepad before repeating the input test.
Only the dedicated AVD is allowed; helpers reject physical-device serials and
other AVD names. Do not use an emulator owned by another task.

## Office setup

Select **Install Microsoft 365** after initializing and testing Wine. The current
development recipe obtains the hash-pinned ODT from Microsoft, extracts it into
private runtime storage, then runs its download/configure commands. The initial
configuration is Microsoft 365 Apps for enterprise (`O365ProPlusRetail`), 64-bit,
English, with a visible installer and normal Microsoft licence acceptance.
Use a licence appropriate to the selected edition. Account sign-in remains with
Microsoft; this app does not implement activation.

This path is being tested, not claimed to work yet. Details and observed failures
belong in [Office compatibility](docs/office-compatibility.md). Word/Excel/
PowerPoint share one prefix. Reset archives that prefix instead of deleting it.

## Upstream components

The PoC uses patched PRoot, talloc, Android shared-memory support, the embedded
Termux:X11 library, Ubuntu Base and Soda 11.0-27. See
[upstream investigation](docs/upstreams.md), [architecture](docs/architecture.md)
and [component licences / source obligations](docs/licenses.md).

## Licence

Original project code is GPL-3.0-or-later. Separately downloaded upstream
components retain their own licences. See [upstream notes](docs/upstreams.md).

Microsoft, Microsoft 365, Word, Excel and PowerPoint are trademarks of Microsoft.
Only project-owned placeholder artwork is used.
