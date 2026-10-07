# Office compatibility investigation

Office is not installed or verified in this project yet. The official ODT has
launched and its `/download` stage is retrieving build 16.0.20430.20146 inside
the standalone app's shared prefix. Do not infer Office
support from a successful APK build, Linux shell or Wine version command.

## Official installation path

Microsoft's [ODT overview](https://learn.microsoft.com/en-us/microsoft-365-apps/deploy/overview-office-deployment-tool)
documents the self-extracting Office Deployment Tool, `setup.exe /download` and
`setup.exe /configure`. Configuration must follow the
[official options](https://learn.microsoft.com/en-us/microsoft-365-apps/deploy/office-deployment-tool-configuration-options).
The planned user flow obtains these files from Microsoft into the private shared
Wine environment, displays the normal setup/licensing experience and retains
installer logs there. Credentials remain in Microsoft's sign-in flow.

On 2026-10-07 the [official download page](https://www.microsoft.com/en-us/download/details.aspx?id=49117)
linked ODT 16.0.20326.20112 (`officedeploymenttool_20326-20112.exe`), published
2026-09-09. The downloaded file has a valid Microsoft Corporation Authenticode
signature and SHA-256
`fbb64358fd4168acd52ee4efe47ffd032b6231dfb415ae2dce61b0e58ba67f86`.
It remains in ignored local downloads; it has not been executed on the Windows
host, committed, uploaded or included in an APK.

## Selected first runner

Soda 11.0-27 experimental x86_64 is a Linux runner with both Unix x86_64 and i386
loaders. Android's Java ABI and Wine's Windows executable architecture are
different concerns: a 64-bit APK can still need i386 guest Linux libraries for
this runner. The package is hash-checked before extraction.

Use the published Soda implementation first. Keep any required Office-specific
registry/DLL/display adjustments in the Office setup script when evidence calls
for them. Do not introduce licensing stubs, fake subscriptions, activation
bypasses or third-party Microsoft DLL downloads.

Account broker, WebView, Click-to-Run, App-V, COM, fonts, ARM64EC/FEX and actual
Word/Excel/PowerPoint compatibility are all unverified on Android at this stage.
