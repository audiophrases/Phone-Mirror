# Builds ConvoRecorder.apk (records + transcribes ChatGPT voice chats via the ClassAudioHook tap). Usage: powershell -File build.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:Path = "C:\Program Files\Java\jdk-26.0.1\bin;" + $env:Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\37.0.0"
$jar = "$sdk\platforms\android-36\android.jar"
$out = "$root\build"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\classes","$out\dex","$out\gen" | Out-Null

# Resources (icon) first: linking also generates R.java for the sources
& "$bt\aapt2.exe" compile --dir "$root\app\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\app\AndroidManifest.xml" --java "$out\gen" "$out\res.zip" --min-sdk-version 26 --target-sdk-version 36 --version-code 2 --version-name 1.1
if ($LASTEXITCODE) { throw "aapt2 link failed" }

javac --release 11 -encoding UTF-8 -cp $jar -d "$out\classes" (Get-ChildItem "$root\app\src","$out\gen" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw "javac failed" }
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName
if ($LASTEXITCODE) { throw "d8 failed" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", "Update")
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex")
$zip.Dispose()
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"

# Same debug key as the hook module
$ks = "$root\..\ClassAudioHook\debug.keystore"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$root\ConvoRecorder.apk" "$out\aligned.apk"
Get-Item "$root\ConvoRecorder.apk" | Select-Object FullName, Length
