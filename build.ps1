# 手工构建 Xposed 模块 APK（不使用 Gradle / AndroidX）
# 用法：pwsh -File build.ps1
$ErrorActionPreference = "Stop"

$root    = $PSScriptRoot
$outDir  = Split-Path -Parent $PSScriptRoot
$tc      = "$env:LOCALAPPDATA\AndroidBuild"
$bt      = "$tc\build-tools\34.0.0"
$androidJar = "$tc\platforms\android-34\android.jar"

$jdkDir = Get-ChildItem -LiteralPath "$tc\jdk17" -Directory | Select-Object -First 1
if (-not $jdkDir) { throw "JDK 未解压到 $tc\jdk17" }
$java   = Join-Path $jdkDir.FullName "bin\java.exe"
$javac  = Join-Path $jdkDir.FullName "bin\javac.exe"
$keytool = Join-Path $jdkDir.FullName "bin\keytool.exe"

$aapt2     = "$bt\aapt2.exe"
$aapt      = "$bt\aapt.exe"
$zipalign  = "$bt\zipalign.exe"
$d8Jar     = "$bt\lib\d8.jar"
$signerJar = "$bt\lib\apksigner.jar"

function Run([string]$name, [scriptblock]$block) {
    Write-Host "== $name" -ForegroundColor Cyan
    & $block
    if ($LASTEXITCODE -ne 0) { throw "$name 失败，退出码 $LASTEXITCODE" }
}

$build = "$root\build"
if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
foreach ($d in @("gen", "classes", "stub", "dex")) {
    New-Item -ItemType Directory -Force -Path "$build\$d" | Out-Null
}

Run "aapt2 compile" { & $aapt2 compile --dir "$root\res" -o "$build\res.zip" }

Run "aapt2 link" { & $aapt2 link -o "$build\base.apk" -I $androidJar `
        --manifest "$root\AndroidManifest.xml" -A "$root\assets" `
        --java "$build\gen" --min-sdk-version 29 --target-sdk-version 34 "$build\res.zip" }

$stubFiles = (Get-ChildItem -LiteralPath "$root\stub" -Recurse -Filter *.java).FullName
Run "javac (stub)" { & $javac -encoding UTF-8 -source 8 -target 8 -nowarn `
        -bootclasspath $androidJar -d "$build\stub" $stubFiles }

$srcFiles = @((Get-ChildItem -LiteralPath "$root\src" -Recurse -Filter *.java).FullName) +
            @((Get-ChildItem -LiteralPath "$build\gen" -Recurse -Filter *.java).FullName)
Run "javac (module)" { & $javac -encoding UTF-8 -source 8 -target 8 -nowarn `
        -bootclasspath $androidJar -classpath "$build\stub" -d "$build\classes" $srcFiles }

$classFiles = (Get-ChildItem -LiteralPath "$build\classes" -Recurse -Filter *.class).FullName
Run "d8 dex" { & $java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 29 `
        --lib $androidJar --output "$build\dex" $classFiles }

Write-Host "== 打包 classes.dex" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\base.apk", [System.IO.Compression.ZipArchiveMode]::Update)
$entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
$dexStream = $entry.Open()
$srcStream = [System.IO.File]::OpenRead("$build\dex\classes.dex")
$srcStream.CopyTo($dexStream)
$srcStream.Dispose()
$dexStream.Dispose()
$zip.Dispose()
Remove-Item -LiteralPath "$build\dex" -Recurse -Force

Run "zipalign" { & $zipalign -f -p 4 "$build\base.apk" "$build\aligned.apk" }

$ks = "$root\sbclock.keystore"
if (-not (Test-Path -LiteralPath $ks)) {
    Run "keytool 生成签名密钥" { & $keytool -genkeypair -keystore $ks -alias sbclock `
            -keyalg RSA -keysize 2048 -validity 10950 `
            -storepass android -keypass android `
            -dname "CN=StatusBarClock, OU=Personal, O=Personal, C=CN" }
}

$apkOut = "$outDir\StatusBarClock-1.0.apk"
if (Test-Path -LiteralPath $apkOut) { Remove-Item -LiteralPath $apkOut -Force }
Run "apksigner 签名" { & $java -jar $signerJar sign --ks $ks `
        --ks-pass pass:android --key-pass pass:android `
        --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
        --out $apkOut "$build\aligned.apk" }

Write-Host "`n== 校验" -ForegroundColor Cyan
& $java -jar $signerJar verify --print-certs $apkOut
& $aapt dump badging $apkOut | Select-String -Pattern "^package|launchable-activity|sdkVersion|xposed"

Write-Host "`n产物：$apkOut  ($([math]::Round((Get-Item -LiteralPath $apkOut).Length/1KB,1)) KB)" -ForegroundColor Green
