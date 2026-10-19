# Windows version of build.sh: builds build\freetv.apk from the bare SDK tools.
# Needs a JDK 17+ and an Android SDK with build-tools and an Android platform (28 or newer).
# The SDK is found through ANDROID_HOME, ANDROID_SDK_ROOT or %LOCALAPPDATA%\Android\Sdk.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$SDK = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
  Where-Object { $_ -and (Test-Path "$_\build-tools") } | Select-Object -First 1
if (-not $SDK) { throw "Android SDK not found: set ANDROID_HOME" }
# android-28 is the target; a newer platform jar compiles the same code
$platform = Get-ChildItem "$SDK\platforms" -Directory | Sort-Object { [int]($_.Name -replace '\D', '') } |
  Where-Object { [int]($_.Name -replace '\D', '') -ge 28 } | Select-Object -First 1
if (-not $platform) { throw "no Android platform 28+ in $SDK - sdkmanager ""platforms;android-28""" }
$JAR = "$($platform.FullName)\android.jar"
$BT = (Get-ChildItem "$SDK\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.]', '') } |
  Select-Object -Last 1).FullName

$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $jdk = "$env:JAVA_HOME\bin" }
elseif ($javac -and (Test-Path (Join-Path (Split-Path $javac.Source) "keytool.exe"))) { $jdk = Split-Path $javac.Source }
else {
  $jdk = Get-ChildItem "C:\Program Files\Java\jdk-*\bin\javac.exe", "C:\Program Files\Eclipse Adoptium\jdk-*\bin\javac.exe" `
    -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1 | ForEach-Object { Split-Path $_.FullName }
}
if (-not $jdk) { throw "no JDK found: install JDK 17+ or set JAVA_HOME" }
$env:Path = "$jdk;$env:Path"

$ver = @{}
Get-Content version.env | Where-Object { $_ -match '^(\w+)=(.*)$' } | ForEach-Object { $ver[$Matches[1]] = $Matches[2] }

# Your own signing key, created on the first build. Keep it and never commit it:
# updates must be signed with the same key.
$KEYSTORE = if ($env:FREETV_KEYSTORE) { $env:FREETV_KEYSTORE } else { "$PWD\release.keystore" }
$KEYPASS = if ($env:FREETV_KEYPASS) { $env:FREETV_KEYPASS } else { "freetvhome" }
if (-not (Test-Path $KEYSTORE)) {
  keytool -genkeypair -keystore $KEYSTORE -alias home -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $KEYPASS -keypass $KEYPASS -dname "CN=FreeTV" | Out-Null
  "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
}

$WORK = Join-Path $env:TEMP "freetv-build"
Remove-Item -Recurse -Force $WORK -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$WORK\gen", "$WORK\classes", build | Out-Null

& "$BT\aapt2.exe" compile --dir res -o "$WORK\res.zip"; if ($LASTEXITCODE) { throw "aapt2 compile" }
& "$BT\aapt2.exe" link -I $JAR --manifest AndroidManifest.xml "$WORK\res.zip" `
  --min-sdk-version 28 --target-sdk-version 28 `
  --version-code $ver.VERSION_CODE --version-name $ver.VERSION_NAME `
  --java "$WORK\gen" -o "$WORK\app.apk"; if ($LASTEXITCODE) { throw "aapt2 link" }

$sources = Get-ChildItem src, "$WORK\gen" -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
Set-Content -Encoding ascii "$WORK\sources.txt" $sources
# -Xlint:-deprecation: a platform newer than 28 flags calls that android-28 does not
javac --release 8 -Xlint:all -Xlint:-options -Xlint:-deprecation -Werror -encoding UTF-8 `
  -classpath $JAR -d "$WORK\classes" "@$WORK\sources.txt"; if ($LASTEXITCODE) { throw "javac" }

$classes = Get-ChildItem "$WORK\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --release --min-api 28 --lib $JAR --output $WORK @classes; if ($LASTEXITCODE) { throw "d8" }

# add classes.dex without touching the entries aapt2 wrote (resources.arsc must stay uncompressed)
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$WORK\app.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$WORK\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

& "$BT\zipalign.exe" -f -p 4 "$WORK\app.apk" "$WORK\aligned.apk"; if ($LASTEXITCODE) { throw "zipalign" }
& "$BT\apksigner.bat" sign --ks $KEYSTORE --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" `
  --out build\freetv.apk "$WORK\aligned.apk"; if ($LASTEXITCODE) { throw "apksigner" }
& "$BT\apksigner.bat" verify build\freetv.apk; if ($LASTEXITCODE) { throw "verify" }
$perms = (& "$BT\aapt2.exe" dump permissions build\freetv.apk | Select-String 'uses-permission').Count
"built build\freetv.apk  $((Get-Item build\freetv.apk).Length) bytes  $perms uses-permission entries"
