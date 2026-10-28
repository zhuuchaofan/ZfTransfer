param(
    [string]$Sdk = 'D:\Android\SDK',
    [string]$Studio = 'C:\Program Files\Android\Android Studio'
)
$ErrorActionPreference = 'Stop'
$project = Join-Path $PSScriptRoot 'ZfTransfer'
$out = Join-Path $PSScriptRoot 'build-local'
$env:JAVA_HOME = Join-Path $Studio 'jbr'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$kotlin = Join-Path $Studio 'plugins\Kotlin\kotlinc'
$tools = Join-Path $Sdk 'build-tools\36.0.0'
$android = Join-Path $Sdk 'platforms\android-36\android.jar'
if (!(Test-Path $android)) { $android = Join-Path $Sdk 'platforms\android-37.0\android.jar' }
New-Item -ItemType Directory -Force $out, "$out\dex", "$out\generated" | Out-Null
function Check-Step { if ($LASTEXITCODE -ne 0) { throw "Build command failed with exit code $LASTEXITCODE" } }
$sources = Get-ChildItem "$project\app\src\main\java" -Recurse -Filter *.kt | ForEach-Object FullName
$tests = Get-ChildItem "$project\app\src\test\java" -Recurse -Filter *.kt | ForEach-Object FullName
$junit = Join-Path $Studio 'lib\junit4.jar'
$hamcrest = Join-Path $Studio 'lib\testFramework.jar'
& "$kotlin\bin\kotlinc.bat" @sources @tests -classpath "$android;$junit" -jvm-target 17 -d "$out\tests.jar"
Check-Step
& $java -cp "$out\tests.jar;$junit;$hamcrest;$kotlin\lib\kotlin-stdlib.jar" org.junit.runner.JUnitCore io.github.noisetta.nikonpull.ptp.PtpDataParserTest io.github.noisetta.nikonpull.ptp.PtpIpClientTransferTest io.github.noisetta.nikonpull.ptp.CameraObjectClassificationTest com.xiaozhu.zftransfer.GalleryInteractionTest | Tee-Object "$out\tests.txt"
Check-Step
& "$kotlin\bin\kotlinc.bat" @sources -classpath $android -jvm-target 17 -d "$out\classes.jar"
Check-Step
& "$tools\aapt2.exe" compile --dir "$project\app\src\main\res" -o "$out\resources.zip"
Check-Step
$manifest = Get-Content "$project\app\src\main\AndroidManifest.xml" -Raw
$manifest = $manifest.Replace('<manifest ', '<manifest package="com.xiaozhu.zftransfer" ')
[IO.File]::WriteAllText("$out\AndroidManifest.xml", $manifest, [Text.UTF8Encoding]::new($false))
& "$tools\aapt2.exe" link -o "$out\unsigned.apk" -I $android --manifest "$out\AndroidManifest.xml" --min-sdk-version 29 --target-sdk-version 36 --version-code 8 --version-name '0.4.0-test' -A "$project\app\src\main\assets" "$out\resources.zip"
Check-Step
& $java -cp "$Studio\plugins\android\lib\r8.jar" com.android.tools.r8.D8 --lib $android --min-api 29 --output "$out\dex" "$out\classes.jar" "$kotlin\lib\kotlin-stdlib.jar" "$kotlin\lib\annotations-13.0.jar"
Check-Step
& "$env:JAVA_HOME\bin\jar.exe" uf "$out\unsigned.apk" -C "$out\dex" classes.dex
Check-Step
& "$tools\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
Check-Step
& "$tools\apksigner.bat" sign --ks "$project\test-keystore.jks" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$PSScriptRoot\ZfTransfer-0.4.0-debug.apk" "$out\aligned.apk"
Check-Step
& "$tools\apksigner.bat" verify --verbose --print-certs "$PSScriptRoot\ZfTransfer-0.4.0-debug.apk" | Tee-Object "$out\signature.txt"
Check-Step
& "$tools\aapt2.exe" dump badging "$PSScriptRoot\ZfTransfer-0.4.0-debug.apk" | Tee-Object "$out\badging.txt"
Check-Step
Get-FileHash "$PSScriptRoot\ZfTransfer-0.4.0-debug.apk" -Algorithm SHA256 | Format-List | Out-File "$out\sha256.txt"
Write-Host 'Built ZfTransfer-0.4.0-debug.apk. No device testing implied.'
