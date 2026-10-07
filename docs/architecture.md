# Architecture and decisions

The final deliverable must run without an installed Termux or Termux:X11
companion app. WinBridge will own runtime storage, process lifecycle and display
integration. Reusing appropriately licensed upstream source is preferable to
writing a new Wine display driver or Linux compatibility layer.

The first experimental backend uses Termux's RUN_COMMAND service, PRoot-Distro,
and Termux:X11 to isolate Wine and Office compatibility on x86_64 Android.
This backend is a diagnostic PoC, not the finished architecture. It must be
replaced before claiming the standalone-APK milestone.

Only one Wine prefix is installed, irrespective of launcher entry. The three
Android entry activities have distinct task affinities. The initial X11 PoC
shares one Windows desktop; independent Android document surfaces remain a
separate verification gate.

Testing uses a dedicated Pixel 9 profile AVD with 32 GB data on the workspace
drive. A different chat owns the pre-existing Pixel_9 AVD; do not modify, stop,
clear or install to that shared emulator. Every ADB action must specify the
verified dedicated emulator serial. Do not restart the shared ADB server.
