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

## Next experiment

Build a small x86_64 Android Wine driver/Surface/input PoC with the matching Wine
build tools and NDK. First prove a Win32 window, keyboard modifiers, pointer
coordinates and scrolling, then retest the Office installation and editors.
The existing Soda package is a glibc Linux build: an Android driver cannot be
assumed to be a drop-in replacement. Preserve the current testable route while
measuring the native one. A direct Windows input bridge is another bounded
experiment if a complete Android Wine build is not yet compatible.

The native path, modern-target PE loading, stylus pressure/tilt and Office with
that path are not implemented or verified yet.
