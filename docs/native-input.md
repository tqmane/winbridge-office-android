# Native Android input direction

The final input path should avoid X11 if it can preserve Office compatibility.
The current embedded X server is a working compatibility experiment; removing
the companion APK alone does not meet the separate native-input objective.

## Current upstream evidence

Inspected [Wine](https://github.com/wine-mirror/wine/tree/59416cf58482d97371d17207ddbfd4d6cc22b347/dlls/wineandroid.drv)
at `59416cf58482d97371d17207ddbfd4d6cc22b347` on 2026-10-07:

- `WineActivity.java` receives Android keyboard, touch, mouse and scroll events.
  Its JNI entry points are `wine_keyboard_event` and `wine_motion_event`.
- `keyboard.c` converts Android key codes and modifier state into Windows input.
  `window.c` delivers those queued events via `NtUserSendHardwareInput`.
  This route does not forward input through an X11 server.
- `device.c`, `window.c` and `opengl.c` integrate Android native windows and
  surfaces. `configure.ac` enables the driver for the `linux-android` host.
- The current motion JNI arguments include coordinates, button state and vertical
  scroll; the inspected signature does not transport pen pressure or tilt.
  Do not claim full stylus support without extending and testing this path.

## Direct-input experiment

An opt-in **Direct Windows input (experimental)** switch now selects an Android
adapter that sends authenticated input to our small `windows-input.exe` helper.
It uses [SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)
for Windows virtual keys, UTF-16 text, pointer buttons/motion and scrolling.
The Lorie view's common input entry points route to this adapter without calling
the X11 input JNI functions. The picture still uses the embedded X server.

The helper accepts only bounded input records on a random loopback TCP port.
A private 32-byte key authenticates every record with HMAC-SHA256, a fresh
connection challenge and an increasing sequence number. No key or input text is
logged. Disconnect releases held keys/buttons; the Android queue is bounded.
Authentication and parser checks run with `python scripts/build_native_input.py`.
On the dedicated Android 16 AVD, `scripts/check_direct_input.py` passed invalid
authentication/replay rejection, Android text, Ctrl+S, the save pathname, Enter
and exact saved-file bytes. The helper reported the matching Win32 injections.
No X11 input injection is used by that test. Keep the switch off by default until
pointer, wheel, IME and focus behavior are verified as well.
Touch/pen currently act as a single pointer. Pressure, tilt, multi-touch, arbitrary
hardware layouts and X11 window-manager decorations are not yet covered.

## Native display experiment

Build a small x86_64 Android Wine driver/Surface/input PoC with the matching Wine
build tools and NDK. First prove a Win32 window, keyboard modifiers, pointer
coordinates and scrolling, then retest the Office installation and editors.
The existing Soda package is a glibc Linux build: an Android driver cannot be
assumed to be a drop-in replacement. Preserve the current testable route while
measuring the native one. A direct Windows input bridge is another bounded
experiment if a complete Android Wine build is not yet compatible.

The native display path, modern-target PE loading, stylus pressure/tilt and Office
with that path are not implemented or verified yet.
