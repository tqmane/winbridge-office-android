# Upstream investigation

Check current source, release assets, licence files and reproducibility before
selecting a component. A report of Linux success is not Android verification.

Initial candidates: Bottles, bottlesdevs/wine (Soda/Vaniglia), Bottles build-tools,
Wine, Wine-Staging, Valve Wine/Proton, Wine-TKG, FEX, Winlator, Box64/Box86,
Hangover, Termux, termux-packages, proot-distro and Termux:X11.

Upstream checkouts live in ignored `upstream/`; pinned revisions, upstream URLs,
licences and applicable patches will be recorded here. An unmodified upstream
does not require a fork. Fork before publishing project-specific upstream changes.

Office compatibility work must preserve genuine Microsoft licence checks and
sign-in. Do not adopt third-party activation or entitlement bypasses.

## Observations, 2026-10-07

| Candidate | Current source observation / decision |
| --- | --- |
| [Bottles](https://github.com/bottlesdevs/Bottles) | Linux desktop manager; reuse runner knowledge, not GTK UI. Checkout `ad69989bdfd1c3a5e831d1b270bda4d41f75ea4e`. |
| [Bottles Wine / Soda](https://github.com/bottlesdevs/wine/tree/soda) | `soda` is the build/patch branch; `master` is a Wine tree. Soda 11.0-27 has public x86_64 and aarch64 releases. Checkout `2a6c0950e2294e088c591a0bf45836778433155d`. |
| [Bottles build-tools](https://github.com/bottlesdevs/build-tools) | Older Vaniglia, staging and TKG tooling; not the current Soda 11 build recipe. Checkout `fcba104217c6daddddebe69d2dc2e39bc9025848`. |
| [Wine](https://github.com/wine-mirror/wine) / [Wine-Staging](https://github.com/wine-staging/wine-staging) | Upstream compatibility layer and optional patch series; not an Android runtime package. |
| [Valve Wine](https://github.com/ValveSoftware/wine) / [Proton](https://github.com/ValveSoftware/Proton) | Soda derives from Valve's work; importing the whole Steam runtime is unnecessary for the first Office test. |
| [Wine-TKG](https://github.com/Frogging-Family/wine-tkg-git) | Used by Soda's current CI. Its x86_64 recipe sets `_NOLIB32=false`; the published runner needs an i386 Linux userland as well. |
| [Winlator](https://github.com/brunodev85/winlator) | Relevant integrated Android display/input/runtime reference. ARM translation is unnecessary for the initial x86_64 AVD. |
| [Termux](https://github.com/termux/termux-app) / [PRoot-Distro](https://github.com/termux/proot-distro) | Useful temporary execution harness. PRoot-Distro now supports OCI and local rootfs archives; this PoC uses a checked Ubuntu Base archive. Final APK cannot require the companion. |
| [Termux:X11](https://github.com/termux/termux-x11) | Has an embeddable `lorie` module, native X server and Android input handling. Chosen for integration; a small GPL fork adds in-service startup. |
| [Box64](https://github.com/ptitSeb/box64) / [Box86](https://github.com/ptitSeb/box86) | CPU translators, not Wine replacements. Box64 is an ARM option; Box86 is lower priority than WoW64. |
| [FEX](https://github.com/FEX-Emu/FEX) / [Hangover](https://github.com/AndreRH/hangover) | ARM64 alternatives. Soda's ARM64 recipe includes `libwow64fex`; inspect and test that runner before adding another translator. No ARM compatibility claim yet. |

## Linux Office reports

Soda [11.0-27 release notes](https://github.com/bottlesdevs/wine/releases/tag/soda-11.0-27-experimental)
identify an Office startup regression fixed from 11.0-26. Its source includes
Office/WinRT smoke programs and authentication work, making the published runner
a smaller initial experiment than rebuilding Wine with selected patches.

[glrotate/office365_w](https://github.com/glrotate/office365_w) and its
[Nix fork](https://github.com/Tombert/office365_flake) describe an ODT/Proton setup
and Word UI with sign-in not verified. Their custom licensing shims are not
adopted. These are Linux reports, not evidence that Office works on Android.
