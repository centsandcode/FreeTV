# Guided installer for people who would rather not type commands: double-click "Install FreeTV.cmd".
# Gets adb (Google's platform-tools) if it is missing, finds the Fire TV on the network, waits for
# the "Allow USB debugging" prompt to be accepted, then runs install.ps1.
#   .\setup.ps1 -Undo        hand HOME back to Amazon's launcher, keep FreeTV installed
#   .\setup.ps1 -Uninstall   hand HOME back and remove FreeTV
param([switch]$Undo, [switch]$Uninstall)
if ($Uninstall) { $Undo = $true }
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$es = (Get-UICulture).TwoLetterISOLanguageName -eq "es"
function T([string]$en, [string]$sp) { if ($es) { $sp } else { $en } }
function Say([string]$en, [string]$sp, [string]$color = "Gray") { Write-Host (T $en $sp) -ForegroundColor $color }
function Done([int]$code) {
  Write-Host ""
  Read-Host (T "Press Enter to close" "Pulsa Enter para cerrar") | Out-Null
  exit $code
}

Write-Host ""
Say "FreeTV installer" "Instalador de FreeTV" "Cyan"
Write-Host ""
Say "Before you go on, on the TV (only the first time):" "Antes de seguir, en la tele (solo la primera vez):" "White"
Say "  1. Settings > My Fire TV > About: press OK on the device name 7 times." `
    "  1. Ajustes > Mi Fire TV > Acerca de: pulsa OK 7 veces sobre el nombre del dispositivo."
Say "  2. Back in My Fire TV > Developer options: turn on 'ADB debugging'." `
    "  2. Vuelve a Mi Fire TV > Opciones para desarrolladores: activa 'Depuracion ADB'."
Say "  3. The TV and this computer must be on the same Wi-Fi network." `
    "  3. La tele y este ordenador tienen que estar en la misma red Wi-Fi."
Write-Host ""
Read-Host (T "Press Enter when it is done" "Pulsa Enter cuando lo tengas") | Out-Null

# --- adb: use one that is already there, else download Google's platform-tools next to our data
$tools = Join-Path $env:LOCALAPPDATA "FreeTV\platform-tools"
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $adb = @("$tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe", "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
if (-not $adb) {
  Say "Downloading adb (Google's Android platform-tools, about 7 MB)..." `
      "Descargando adb (Android platform-tools de Google, unos 7 MB)..."
  $zip = Join-Path $env:TEMP "platform-tools-latest-windows.zip"
  try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip -DestinationPath (Split-Path $tools) -Force
    Remove-Item $zip -Force
  } catch {
    Say "Could not download adb: $($_.Exception.Message)" "No se pudo descargar adb: $($_.Exception.Message)" "Red"
    Done 1
  }
  $adb = "$tools\adb.exe"
}
# install.ps1 looks for adb on the PATH: put the one we found first, for this window only
$env:Path = "$(Split-Path $adb);$env:Path"
$prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
& $adb start-server 2>&1 | Out-Null
$ErrorActionPreference = $prev

# --- find the TV: with ADB debugging on, a Fire TV listens on port 5555
function Find-Tvs {
  $mine = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' -and $_.PrefixLength -ge 16 } |
    Select-Object -ExpandProperty IPAddress
  $probes = @()
  foreach ($ip in $mine) {
    $base = $ip -replace '\.\d+$', ''
    foreach ($n in 1..254) {
      $addr = "$base.$n"
      if ($addr -eq $ip) { continue }
      $c = New-Object Net.Sockets.TcpClient
      $probes += [pscustomobject]@{ Ip = $addr; Client = $c; Task = $c.ConnectAsync($addr, 5555) }
    }
  }
  Start-Sleep -Milliseconds 1500
  $found = @($probes | Where-Object { $_.Task.Status -eq 'RanToCompletion' -and $_.Client.Connected } | ForEach-Object { $_.Ip })
  foreach ($p in $probes) { $p.Client.Close() }
  return $found
}

Say "Looking for the TV on the network..." "Buscando la tele en la red..."
$tvs = @(Find-Tvs)
$tv = $null
if ($tvs.Count -eq 1) {
  $tv = $tvs[0]
  Say "Found: $tv" "Encontrada: $tv" "Green"
} elseif ($tvs.Count -gt 1) {
  Say "Found several devices with ADB on:" "He encontrado varios dispositivos con ADB activado:" "White"
  for ($i = 0; $i -lt $tvs.Count; $i++) { Write-Host "  $($i + 1). $($tvs[$i])" }
  $pick = Read-Host (T "Which one is the TV? (number)" "Cual es la tele? (numero)")
  if ($pick -match '^\d+$' -and [int]$pick -ge 1 -and [int]$pick -le $tvs.Count) { $tv = $tvs[[int]$pick - 1] }
}
if (-not $tv) {
  if ($tvs.Count -eq 0) {
    Say "I could not find it on my own. Check that 'ADB debugging' is on." `
        "No la he encontrado sola. Comprueba que 'Depuracion ADB' esta activada." "Yellow"
  }
  Say "The TV shows its address in Settings > My Fire TV > About > Network (something like 192.168.1.50)." `
      "La tele muestra su direccion en Ajustes > Mi Fire TV > Acerca de > Red (algo como 192.168.1.50)."
  $tv = (Read-Host (T "Type the TV's IP address" "Escribe la IP de la tele")).Trim()
  if ($tv -notmatch '^\d{1,3}(\.\d{1,3}){3}(:\d+)?$') {
    Say "That does not look like an IP address." "Eso no parece una direccion IP." "Red"
    Done 1
  }
}
$target = if ($tv -match ':') { $tv } else { "${tv}:5555" }

# --- connect, and wait while the TV asks whether to trust this computer
function State {
  $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
  $s = (& $adb -s $target get-state 2>&1 | Select-Object -Last 1) -as [string]
  $ErrorActionPreference = $prev
  return $s
}
$prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
& $adb connect $target 2>&1 | Out-Null
$ErrorActionPreference = $prev
$asked = $false
$deadline = (Get-Date).AddSeconds(120)
while ((State) -notmatch '^device') {
  if ((Get-Date) -gt $deadline) {
    Say "The TV did not answer. Check 'ADB debugging' and the IP address, then try again." `
        "La tele no ha respondido. Revisa 'Depuracion ADB' y la IP, y vuelve a intentarlo." "Red"
    Done 1
  }
  if (-not $asked) {
    Write-Host ""
    Say "Look at the TV: tick 'Always allow from this computer' and choose OK." `
        "Mira la tele: marca 'Permitir siempre desde este ordenador' y pulsa Aceptar." "Yellow"
    $asked = $true
  }
  Start-Sleep 2
  # a refused or timed-out connection needs a fresh connect
  if ((State) -notmatch 'unauthorized|device') {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    & $adb connect $target 2>&1 | Out-Null
    $ErrorActionPreference = $prev
  }
}
Say "Connected to the TV." "Conectado a la tele." "Green"
Write-Host ""

# --- the actual install (or undo)
try {
  # when uninstalling, install.ps1's "FreeTV stays installed" note would only confuse
  if ($Uninstall) { & "$PSScriptRoot\install.ps1" -Tv $target -Undo | Out-Null }
  elseif ($Undo) { & "$PSScriptRoot\install.ps1" -Tv $target -Undo }
  else { & "$PSScriptRoot\install.ps1" -Tv $target }
  if ($Uninstall) {
    $out = (& $adb -s $target uninstall tv.freetv.home 2>&1 | Out-String)
    if ($out -notmatch "Success") { throw "adb uninstall failed: $out" }
  }
} catch {
  Write-Host ""
  Say "Something went wrong:" "Algo ha fallado:" "Red"
  Write-Host $_.Exception.Message -ForegroundColor Red
  Done 1
}
Write-Host ""
if ($Uninstall) {
  Say "Done. FreeTV is removed and HOME opens Amazon's home screen again." "Listo. FreeTV esta desinstalado y el boton Inicio vuelve a abrir la pantalla de Amazon." "Green"
} elseif ($Undo) {
  Say "Done. The HOME button opens Amazon's home screen again." "Listo. El boton Inicio vuelve a abrir la pantalla de Amazon." "Green"
} else {
  Say "Done! Press HOME on the remote to see FreeTV." "Listo! Pulsa Inicio en el mando para ver FreeTV." "Green"
}
Done 0
