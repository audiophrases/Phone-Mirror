# Builds ClassAudioHook.apk (Xposed/Vector module). Usage: powershell -File build.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:Path = "C:\Program Files\Java\jdk-26.0.1\bin;" + $env:Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\37.0.0"
$jar = "$sdk\platforms\android-36\android.jar"
$out = "$root\build"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\stubs","$out\classes","$out\dex" | Out-Null

# Xposed API stubs: compile-time only, never packaged (the framework supplies the real ones)
javac --release 11 -d "$out\stubs" (Get-ChildItem "$root\module\stubs" -Recurse -Filter *.java).FullName
javac --release 11 -cp "$out\stubs;$jar" -d "$out\classes" (Get-ChildItem "$root\module\src" -Recurse -Filter *.java).FullName
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\module\AndroidManifest.xml" -A "$root\module\assets" --min-sdk-version 26 --target-sdk-version 36
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", "Update")
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex")
$zip.Dispose()
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"

$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    keytool -genkeypair -keystore $ks -storepass android -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ClassAudio" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$root\ClassAudioHook.apk" "$out\aligned.apk"
Get-Item "$root\ClassAudioHook.apk" | Select-Object FullName, Length
