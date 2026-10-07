# Architecture and decisions

The final deliverable must run without an installed Termux or Termux:X11
companion app. WinBridge owns runtime storage, process lifecycle and display
integration. The preferred final input path is Android touch, keyboard, mouse
and stylus events delivered to Wine without an X11 input hop, if a tested native
backend can preserve Office compatibility. Investigate Wine's Android driver and
existing Surface/input bridges before choosing that backend. The embedded X11
implementation remains a compatibility PoC, not a requirement for the final
input architecture. Reuse appropriately licensed upstream source where possible.

The current APK owns a private Linux rootfs, PRoot process host and embedded
display. It runs without either Termux companion package. The old RUN_COMMAND
experiment remains under `experiments/` and is not packaged. Linux package setup
uses simulated root; normal Wine sessions run as the actual app UID, with their
Windows profile name preserved. The opt-in direct input adapter bypasses X11
input forwarding while retaining the existing display for compatibility tests.

Only one Wine prefix is installed, irrespective of launcher entry. The three
Android entry activities have distinct task affinities. The initial X11 PoC
shares one Windows desktop; independent Android document surfaces remain a
separate verification gate.

Testing uses a dedicated Pixel 9 profile AVD with 32 GB data on the workspace
drive. A different chat owns the pre-existing Pixel_9 AVD; do not modify, stop,
clear or install to that shared emulator. Every ADB action must specify the
verified dedicated emulator serial. Do not restart the shared ADB server.
Physical devices are explicitly out of scope: never install,
launch, inspect, reboot or otherwise operate on them. The test helper rejects
physical-device serials before issuing any ADB call.
