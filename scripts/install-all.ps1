# Installs APKs through app_process + PackageInstaller API, bypassing blocked pm/cmd package.
$ErrorActionPreference = "Continue"

$root = Split-Path $PSScriptRoot -Parent
$apkDir = Join-Path $root "apks"

function Find-Adb {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }

  $winget = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
  if (Test-Path $winget) { return $winget }

  return $null
}

function Find-HelperDex {
  $candidates = @(
    (Join-Path $root "gloader-installer.dex"),
    (Join-Path $root "helper\installer.dex"),
    (Join-Path $root "build\installer-helper\gloader-installer.dex")
  )
  foreach ($candidate in $candidates) {
    if (Test-Path $candidate) { return $candidate }
  }

  $releaseDex = Get-ChildItem $root -Filter "gloader-installer*.dex" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
  if ($releaseDex) { return $releaseDex.FullName }

  return $null
}

function Invoke-AdbShellInteractive([string]$command, [int]$timeoutSeconds = 600) {
  $job = Start-Job {
    param($adbPath, $shellCommand)
    "$shellCommand`nexit`n" | & $adbPath shell 2>&1
  } -ArgumentList $script:adb, $command

  if (Wait-Job $job -Timeout $timeoutSeconds) {
    $result = Receive-Job $job
  } else {
    $result = "__TIMEOUT__"
    Stop-Job $job
  }
  Remove-Job $job -Force
  return ($result | Out-String)
}

$adb = Find-Adb
if (-not $adb) {
  Write-Host "ADB not found. Install it with: winget install Google.PlatformTools" -ForegroundColor Red
  Write-Host "Close and reopen this window after installation." -ForegroundColor Red
  exit 1
}

Write-Host "=== 1. Checking ADB connection ===" -ForegroundColor Cyan
& $adb start-server 2>&1 | Out-Null
Start-Sleep 1
if ("$(& $adb get-state 2>&1)" -notmatch "^device") {
  Write-Host "Head unit is not connected over ADB." -ForegroundColor Red
  Write-Host "Open ADB on the head unit and connect the data cable." -ForegroundColor Yellow
  exit 1
}
& $adb devices

$dex = Find-HelperDex
if (-not $dex) {
  Write-Host "Helper dex not found. Put gloader-installer-1.X.dex next to install.bat." -ForegroundColor Red
  exit 1
}

$apks = @()
if (Test-Path $apkDir) {
  $apks = Get-ChildItem $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue
}
if (-not $apks) {
  $apks = Get-ChildItem $root -Filter "GLoader-*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "GLoader-installer-*.apk" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
}
if (-not $apks) {
  Write-Host "No APK found. Put APK files into apks\ or put GLoader-1.X.apk next to install.bat." -ForegroundColor Yellow
  exit 1
}

Write-Host "`n=== 2. Uploading helper and APKs ($($apks.Count)) ===" -ForegroundColor Cyan
& $adb push $dex /data/local/tmp/installer.dex 2>&1 | Out-Null
[void](Invoke-AdbShellInteractive "rm -rf /data/local/tmp/apks; mkdir -p /data/local/tmp/apks" 120)

$remoteApks = @()
foreach ($apk in $apks) {
  $safeName = $apk.Name -replace "[^A-Za-z0-9._-]", "_"
  $remote = "/data/local/tmp/apks/$safeName"
  & $adb push "$($apk.FullName)" $remote 2>&1 | Out-Null
  $remoteApks += $remote
  Write-Host "  + $($apk.Name)"
}

Write-Host "`n=== 3. Installing through PackageInstaller helper ===" -ForegroundColor Cyan
$run = "CLASSPATH=/data/local/tmp/installer.dex app_process /system/bin Installer " + ($remoteApks -join " ")
Write-Host (Invoke-AdbShellInteractive $run 600)

Write-Host "=== 4. Installed third-party packages ===" -ForegroundColor Cyan
Write-Host (Invoke-AdbShellInteractive "pm list packages -3" 120)

Write-Host "`nDone." -ForegroundColor Green
