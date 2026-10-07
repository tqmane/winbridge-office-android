param(
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Name = 'WinBridge_Pixel9_API36',
    [int]$Port = 5580,
    [ValidateRange(1, 8)][int]$Cores = 2,
    [ValidateRange(2048, 8192)][int]$MemoryMB = 3072,
    [ValidateSet('auto', 'host', 'software')][string]$Gpu = 'host',
    [string]$Image = 'system-images;android-36.1;google_apis_playstore;x86_64',
    [string]$AvdDirectory = "$PSScriptRoot/../.local/avd/WinBridge_Pixel9_API36.avd"
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$manager = Join-Path $Sdk 'cmdline-tools/latest/bin/avdmanager.bat'
$emulator = Join-Path $Sdk 'emulator/emulator.exe'
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    $env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
}
$serial = "emulator-$Port"
$avdRoot = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "$env:USERPROFILE/.android/avd" }
$AvdDirectory = [IO.Path]::GetFullPath($AvdDirectory)
$config = Join-Path $AvdDirectory 'config.ini'
if (!(Test-Path -LiteralPath $config)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $AvdDirectory) | Out-Null
    'no' | & $manager create avd --name $Name --package $Image --device pixel_9 --path $AvdDirectory
    if ($LASTEXITCODE) { throw 'AVD creation failed. Install the specified system image first.' }
    $settings = Get-Content -LiteralPath $config
    $overrides = @{
        'disk.dataPartition.size' = '32G'; 'hw.ramSize' = "$MemoryMB"; 'hw.cpu.ncore' = "$Cores"
        'hw.keyboard' = 'yes'; 'showDeviceFrame' = 'no'; 'hw.gpu.enabled' = 'yes'
    }
    foreach ($key in $overrides.Keys) {
        $settings = @($settings | Where-Object { $_ -notmatch "^$([regex]::Escape($key))=" }) + "$key=$($overrides[$key])"
    }
    Set-Content -LiteralPath $config -Value $settings -Encoding utf8
}
$devices = & $adb devices
if (!($devices -match "^$serial\s+device")) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port,($Port + 1) -ErrorAction SilentlyContinue) {
        throw "Emulator ports $Port/$($Port + 1) are occupied; choose a free even port."
    }
    $logs = Join-Path $PSScriptRoot '../artifacts'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $process = Start-Process -FilePath $emulator -ArgumentList @('-avd', $Name, '-port', $Port,
        '-no-snapshot', '-no-boot-anim', '-no-audio', '-no-window', '-gpu', $Gpu,
        '-cores', $Cores, '-memory', $MemoryMB) `
        -WindowStyle Hidden -RedirectStandardOutput "$logs/emulator.stdout.log" `
        -RedirectStandardError "$logs/emulator.stderr.log" -PassThru
}
$deadline = (Get-Date).AddMinutes(4)
do {
    Start-Sleep -Seconds 2
    if ($process -and $process.HasExited) { throw 'Emulator exited; inspect artifacts/emulator.*.log.' }
    $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    if ((Get-Date) -gt $deadline) { throw 'Emulator boot timed out; inspect artifacts/emulator.*.log.' }
} until ($boot -match '^1')
$actualName = & $adb -s $serial emu avd name
if ($actualName[0].Trim() -ne $Name) { throw "Port belongs to another AVD: $($actualName[0])" }
$runtime = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'qemu-system-x86_64-headless.exe' -and
    $_.CommandLine -match "-avd $([regex]::Escape($Name)) -port $Port(?:\s|$)"
})
if ($runtime.Count -eq 1) { (Get-Process -Id $runtime[0].ProcessId).PriorityClass = 'BelowNormal' }
& $adb -s $serial shell wm size 2560x1600
& $adb -s $serial shell wm density 240
& $adb -s $serial shell settings put system accelerometer_rotation 0
& $adb -s $serial shell settings put system user_rotation 0
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $serial shell input keyevent KEYCODE_MENU
& $adb -s $serial shell wm size
& $adb -s $serial shell wm density
& $adb -s $serial shell df -h /data
