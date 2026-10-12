param(
    [ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [string]$AdbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$package = 'io.github.slman890.localcallassistant'
$fixturePackage = "$package.test"
$artifactDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot '../../work/phone-device'))
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
function Invoke-Adb {
    param([string[]]$Arguments)
    $lines = & $AdbPath -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
    return $lines
}
$hardware = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.hardware') | Out-String).Trim()
if ($hardware -notin @('ranchu', 'goldfish')) { throw 'This test only runs on an emulator, never a physical phone.' }
Invoke-Adb -Arguments @('install', '-r', (Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk')) | Out-Null
Invoke-Adb -Arguments @('install', '-r', (Join-Path $repoRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) | Out-Null
$previousDialer = (Invoke-Adb -Arguments @('shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.DIALER') | Out-String).Trim()
$localeText = Invoke-Adb -Arguments @('shell', 'cmd', 'locale', 'get-app-locales', $package, '--user', '0') | Out-String
if ($localeText -notmatch '\[(?<locales>[^\]]*)\]') { throw 'Could not capture the previous app locales.' }
$previousLocales = $Matches.locales.Replace(' ', '')
$succeeded = $false
try {
    # Role changes can kill a process when permissions are revoked. Configure BEFORE instrumentation.
    Invoke-Adb -Arguments @('shell', 'cmd', 'role', 'add-role-holder', '--user', '0', 'android.app.role.DIALER', $package) | Out-Null
    foreach ($permission in @('CALL_PHONE', 'READ_PHONE_STATE', 'READ_CONTACTS', 'READ_CALL_LOG', 'POST_NOTIFICATIONS')) {
        Invoke-Adb -Arguments @('shell', 'pm', 'grant', $package, "android.permission.$permission") | Out-Null
    }
    Invoke-Adb -Arguments @('shell', 'cmd', 'locale', 'set-app-locales', $package, '--user', '0', '--locales', 'ar') | Out-Null
    $result = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.baba.callvault.dialer.TelecomPhoneInstrumentedTest',
        "$fixturePackage/androidx.test.runner.AndroidJUnitRunner") | Out-String
    $result | Set-Content -LiteralPath (Join-Path $artifactDirectory 'instrumentation.log') -Encoding utf8
    Write-Output $result
    $succeeded = $result -match 'OK \(3 tests\)' -and $result -notmatch 'Process crashed|FAILURES'
    if ($succeeded) { foreach ($name in @('phone-main-ar', 'phone-incoming-ar', 'phone-active-ar')) {
        $remote = "/sdcard/Android/data/$package/files/phone-test/$name.png"
        Invoke-Adb -Arguments @('pull', $remote, (Join-Path $artifactDirectory "$name.png")) | Out-Null
    } }
} finally {
    foreach ($id in @('local-fixture-a', 'local-fixture-b')) {
        & $AdbPath -s $Serial shell cmd telecom unregister-phone-account "$fixturePackage/com.baba.callvault.dialer.TestPhoneConnectionService" $id 0 | Out-Null
    }
    if ($previousDialer) {
        Invoke-Adb -Arguments @('shell', 'cmd', 'role', 'add-role-holder', '--user', '0', 'android.app.role.DIALER', $previousDialer) | Out-Null
    } else {
        Invoke-Adb -Arguments @('shell', 'cmd', 'role', 'remove-role-holder', '--user', '0', 'android.app.role.DIALER', $package) | Out-Null
    }
    $localeArguments = @('shell', 'cmd', 'locale', 'set-app-locales', $package, '--user', '0')
    if ($previousLocales) { $localeArguments += @('--locales', $previousLocales) }
    Invoke-Adb -Arguments $localeArguments | Out-Null
    Write-Output ('Restored default dialer: ' + ((Invoke-Adb -Arguments @('shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.DIALER')) | Out-String).Trim())
}
if (-not $succeeded) { throw 'The Telecom phone tests did not pass. See work/phone-device/instrumentation.log.' }
