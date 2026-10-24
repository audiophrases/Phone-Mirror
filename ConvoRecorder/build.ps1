# Builds ConvoRecorder.apk (records + transcribes ChatGPT voice chats via the ClassAudioHook tap). Usage: powershell -File build.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$env:Path = "C:\Program Files\Java\jdk-26.0.1\bin;" + $env:Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\37.0.0"
$jar = "$sdk\platforms\android-36\android.jar"
$out = "$root\build"
$libs = "$root\libs"

# On-device speech recognition: sherpa-onnx (Kotlin API + JNI, needs the Kotlin stdlib). Fetched once, pinned by hash.
function Fetch($url, $file, $sha256) {
    if ((Test-Path $file) -and (Get-FileHash $file -Algorithm SHA256).Hash -eq $sha256) { return }
    Write-Host "Downloading $url"
    Invoke-WebRequest $url -OutFile "$file.part" -UseBasicParsing
    if ((Get-FileHash "$file.part" -Algorithm SHA256).Hash -ne $sha256) { throw "checksum mismatch: $url" }
    Move-Item "$file.part" $file -Force
}
New-Item -ItemType Directory -Force "$libs\lib\arm64-v8a" | Out-Null
Fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar" "$libs\sherpa-onnx-1.13.8.aar" "633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96"
Fetch "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar" "$libs\kotlin-stdlib-1.9.24.jar" "858B902696DA9CF585AB9D98FFC1C2712269828354DFE9107E3711B084A36468"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$aar = [IO.Compression.ZipFile]::OpenRead("$libs\sherpa-onnx-1.13.8.aar")
foreach ($e in $aar.Entries) {
    # The JNI library only links against onnxruntime; the C/C++ API libraries are not needed.
    $dest = switch ($e.FullName) {
        "classes.jar" { "$libs\sherpa-onnx-1.13.8.jar" }
        "jni/arm64-v8a/libsherpa-onnx-jni.so" { "$libs\lib\arm64-v8a\libsherpa-onnx-jni.so" }
        "jni/arm64-v8a/libonnxruntime.so" { "$libs\lib\arm64-v8a\libonnxruntime.so" }
    }
    if ($dest -and -not (Test-Path $dest)) { [IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest) }
}
$aar.Dispose()
$deps = "$libs\sherpa-onnx-1.13.8.jar", "$libs\kotlin-stdlib-1.9.24.jar"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory "$out\classes","$out\dex","$out\gen" | Out-Null

# Resources (icon) first: linking also generates R.java for the sources
& "$bt\aapt2.exe" compile --dir "$root\app\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\app\AndroidManifest.xml" --java "$out\gen" "$out\res.zip" --min-sdk-version 26 --target-sdk-version 36 --version-code 3 --version-name 2.0
if ($LASTEXITCODE) { throw "aapt2 link failed" }

javac --release 11 -encoding UTF-8 -cp "$jar;$($deps -join ';')" -d "$out\classes" (Get-ChildItem "$root\app\src","$out\gen" -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw "javac failed" }
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName $deps
if ($LASTEXITCODE) { throw "d8 failed" }

$zip = [IO.Compression.ZipFile]::Open("$out\base.apk", "Update")
foreach ($dex in Get-ChildItem "$out\dex" -Filter *.dex) {
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex.FullName, $dex.Name)
}
foreach ($so in Get-ChildItem "$libs\lib\arm64-v8a" -Filter *.so) {
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $so.FullName, "lib/arm64-v8a/$($so.Name)")
}
$zip.Dispose()
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"

# Same debug key as the hook module
$ks = "$root\..\ClassAudioHook\debug.keystore"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$root\ConvoRecorder.apk" "$out\aligned.apk"
Get-Item "$root\ConvoRecorder.apk" | Select-Object FullName, Length
