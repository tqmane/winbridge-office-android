# WinBridge Office for Android

Experimental, tablet-first launchers for Windows Word, Excel and PowerPoint on
Android, using one shared Wine prefix. This is an independent project, not a
Microsoft, Wine, Bottles or Termux product.

## Status

Development has just started. No Wine or Office runtime is verified yet.
The first target is an Android 16 x86_64 Pixel 9 AVD with a tablet-size landscape
display. Windows GUI and Office compatibility are separate verification gates.
ARM64 is a later target, not currently supported.

## Scope

- Reuse upstream runtime and display implementations before writing new ones.
- Verify Wine boot, a Win32 GUI, input and relaunch before testing Office.
- Share the runtime and prefix across three Android launcher entries.
- Obtain Office from Microsoft on the user's device. A valid Office licence is
  required; activation remains Microsoft's normal process.
- Never distribute Microsoft installers, Office binaries, branding or licences.

## Development

Build, emulator and Office setup instructions will be added as their paths are
implemented and tested. See [the evidence ledger](docs/testing.md) for results;
unfinished items are not claims of support.

## Licence

Original project code is GPL-3.0-or-later. Separately downloaded upstream
components retain their own licences. See [upstream notes](docs/upstreams.md).

Microsoft, Microsoft 365, Word, Excel and PowerPoint are trademarks of Microsoft.
Only project-owned placeholder artwork is used.
