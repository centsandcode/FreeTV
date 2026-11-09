# Windows version of install.sh: installs FreeTV and gives it the HOME button.
#   .\install.ps1 -Tv 192.168.1.50         the release APK (checksum-verified), else build from source
#   .\install.ps1 -Tv 192.168.1.50 -Build  build from source (needs a JDK and the Android SDK)
#   .\install.ps1 -Tv 192.168.1.50 -Undo   hand HOME back to Amazon's launcher
# Without -Tv it uses the single device adb already sees.
param([string]$Tv, [switch]$Undo, [switch]$Build, [switch]$NoBuild)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$PKG = "tv.freetv.home"
$GUARD = "$PKG/$PKG.Guard"
$MEDIA = "$PKG/$PKG.Media"
# launchers that took HOME the same way before: their service is swapped for ours
$OLD_GUARDS = @("tv.dumbtv.home/tv.dumbtv.home.Guard",
  "com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService")

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path "$_\platform-tools\adb.exe") } | Select-Object -First 1
  if ($sdk) { $adb = "$sdk\platform-tools\adb.exe" } else { throw "adb not found: install Android platform-tools" }
}
if ($Tv) {
  $target = if ($Tv -match ':') { $Tv } else { "${Tv}:5555" }
  & $adb connect $target | Out-Null
  $serial = @("-s", $target)
} else { $serial = @() }
function sh_([string]$cmd) { & $adb @serial shell $cmd }
if ((& $adb @serial get-state 2>$null) -ne "device") {
  throw "no TV connected: enable ADB debugging on the TV, run with -Tv <ip> and accept the prompt on screen"
}

function Set-Futurity([bool]$on) {
  $cur = (sh_ "settings get global alarm_manager_constants").Trim()
  $parts = @(if ($cur -ne "null" -and $cur) { $cur.Split(',') | Where-Object { $_ -and $_ -notlike 'min_futurity=*' } })
  if ($on) { $parts += "min_futurity=0" }
  if ($parts.Count) { sh_ "settings put global alarm_manager_constants $($parts -join ',')" }
  else { sh_ "settings delete global alarm_manager_constants" | Out-Null }
}

# Puts $svc (or nothing) in place of any launcher's service, keeping every other service.
function Set-Guard([string]$svc) {
  $cur = (sh_ "settings get secure enabled_accessibility_services").Trim()
  $keep = @(if ($cur -ne "null" -and $cur) {
    $cur.Split(':') | Where-Object { $_ -and $_ -ne $GUARD -and $OLD_GUARDS -notcontains $_ } })
  if ($svc) { $keep += $svc }
  # clearing first makes the accessibility manager rebind a service whose process died
  sh_ "settings put secure enabled_accessibility_services '\`"\`"'"
  Start-Sleep 1
  if ($keep.Count) {
    sh_ "settings put secure enabled_accessibility_services $($keep -join ':')"
    sh_ "settings put secure accessibility_enabled 1"
  } else {
    sh_ "settings put secure accessibility_enabled 0"
  }
}

if ($Undo) {
  Set-Futurity $false
  Set-Guard ""
  sh_ "cmd notification disallow_listener $MEDIA" | Out-Null
  sh_ "am start -a android.intent.action.MAIN -c android.intent.category.HOME" | Out-Null
  "HOME handed back to Amazon's launcher (FreeTV stays installed: adb uninstall $PKG)"
  return
}

# version.env is the single source of truth: with a checksum there we install the
# published APK, and without one there is nothing to verify, so we build from source.
$ver = @{}
foreach ($line in Get-Content "$PSScriptRoot\version.env") {
  if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$') { $ver[$Matches[1]] = $Matches[2].Trim() }
}
$VERSION = $ver['VERSION']; $APK_SHA256 = $ver['APK_SHA256']

$mode = if ($NoBuild) { "none" } elseif ($Build -or -not $APK_SHA256) { "build" } else { "release" }

if ($mode -eq "build") {
  & "$PSScriptRoot\build.ps1"
  $src = "$PSScriptRoot\build\freetv.apk"
} elseif ($mode -eq "release") {
  New-Item -ItemType Directory -Force -Path "$PSScriptRoot\build" | Out-Null
  $src = "$PSScriptRoot\build\freetv-$VERSION.apk"
  $url = "https://github.com/centsandcode/FreeTV/releases/download/v$VERSION/freetv.apk"
  $cached = (Test-Path $src) -and ((Get-FileHash $src -Algorithm SHA256).Hash -eq $APK_SHA256)
  if (-not $cached) {
    "downloading FreeTV $VERSION"
    try { Invoke-WebRequest -Uri $url -OutFile "$src.part" -UseBasicParsing }
    catch { throw "could not download $url" }
    Move-Item "$src.part" $src -Force
  }
  $got = (Get-FileHash $src -Algorithm SHA256).Hash
  if ($got -ne $APK_SHA256) { throw "checksum mismatch for $src (got $got) - not installing" }
  "checksum ok"
} else {
  $src = "$PSScriptRoot\build\freetv.apk"
}

# adb on Windows fails on long paths: install from a short copy
$apk = Join-Path $env:TEMP "freetv.apk"
Copy-Item $src $apk -Force
$out = & $adb @serial install -r $apk 2>&1 | Select-Object -Last 1
if ("$out" -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
  throw "the installed FreeTV was signed with a different key: back up /sdcard/Android/data/$PKG/files/home.conf, adb uninstall $PKG, run again"
}
if ("$out" -notmatch "Success") { throw "adb install failed: $out" }
"installed $PKG"

# Coming from dumbtv home: bring its settings and wallpapers along, once.
$old = "/sdcard/Android/data/tv.dumbtv.home/files"; $new = "/sdcard/Android/data/$PKG/files"
if ((sh_ "pm path tv.dumbtv.home") -and -not (sh_ "ls $new/home.conf 2>/dev/null")) {
  sh_ "mkdir -p $new && cp -r $old/. $new/"
  "copied dumbtv home settings (uninstall it when happy: adb uninstall tv.dumbtv.home)"
}

sh_ "dumpsys deviceidle whitelist +$PKG" | Out-Null
sh_ "cmd package set-home-activity $PKG/.Home" 2>$null | Out-Null
Set-Futurity $true
Set-Guard $GUARD
# Now playing reads the active media session; Android hands it only to an enabled notification listener.
sh_ "cmd notification allow_listener $MEDIA" | Out-Null
sh_ "am start -W -n $PKG/.Home" | Out-Null
Start-Sleep 2
"accessibility: $(sh_ 'settings get secure enabled_accessibility_services')"
"front window:  $((sh_ 'dumpsys window | grep -m1 mCurrentFocus') -replace '.* u0 ', '' -replace '}$', '')"
