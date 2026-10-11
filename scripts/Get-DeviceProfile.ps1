# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only device inventory. No calls, recordings, permission grants or remote uploads.
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$OutputPath = (Join-Path $PSScriptRoot '../test-evidence/private/device-profile.json')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adbPath = Join-Path $SdkRoot 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand) { throw 'ADB not found. Supply -SdkRoot for the installed Android SDK.' }
    $adbPath = $adbCommand.Source
}

$deviceLines = @(& $adbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'ADB inventory failed.' }
$deviceIds = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
})
if (-not $Serial) {
    if ($deviceIds.Count -eq 0) { throw 'No authorized Android device. Connect USB, unlock the phone and authorize USB debugging.' }
    if ($deviceIds.Count -gt 1) { throw 'More than one authorized device. Specify -Serial; serials are not written to the report.' }
    $Serial = $deviceIds[0]
} elseif ($Serial -notin $deviceIds) {
    throw 'The selected device is not connected and authorized.'
}

function Read-Property([string]$PropertyName) {
    $value = @(& $adbPath -s $Serial shell getprop $PropertyName)
    if ($LASTEXITCODE -ne 0) { throw "Could not read device property $PropertyName." }
    return ($value -join '').Trim()
}
$profile = [ordered]@{
    collectedAt = [DateTimeOffset]::UtcNow.ToString('o')
    manufacturer = Read-Property 'ro.product.manufacturer'
    model = Read-Property 'ro.product.model'
    androidVersion = Read-Property 'ro.build.version.release'
    apiLevel = Read-Property 'ro.build.version.sdk'
    securityPatch = Read-Property 'ro.build.version.security_patch'
    abiList = Read-Property 'ro.product.cpu.abilist'
    memTotalKiB = $null
    recordingGate = 'NOT_TESTED'
    bluetoothProfile = 'UNKNOWN'
}
$memoryLines = @(& $adbPath -s $Serial shell cat /proc/meminfo)
if ($LASTEXITCODE -eq 0) {
    foreach ($line in $memoryLines) {
        if ($line -match '^MemTotal:\s+(\d+)\s+kB') { $profile.memTotalKiB = [long]$Matches[1]; break }
    }
}
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$parentFolder = Split-Path -Parent $resolvedOutput
$null = New-Item -ItemType Directory -Path $parentFolder -Force
$profile | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $resolvedOutput -Encoding utf8
Write-Output "Device profile saved locally: $resolvedOutput"
Write-Output 'No call recording or Bluetooth acceptance test was performed.'
