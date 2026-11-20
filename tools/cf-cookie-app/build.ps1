# Build dell'APK senza Android Studio / Gradle.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#
# Serve solo l'Android SDK (aapt2, d8, zipalign, apksigner) e un JDK (javac,
# keytool). L'APK firmato finisce in build-local/CFCookie.apk e va installato a
# mano (adb install -r oppure copiandolo sul telefono).

param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }),
    [string]$MinSdk = "24",
    [string]$TargetSdk = "35"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "build-local"
# .tools/ sopravvive alle build (build-local/ viene cancellata ogni volta):
# contiene l'R8 scaricato e la keystore di firma.
$toolsDir = Join-Path $root ".tools"
$gen = Join-Path $out "gen"
$classes = Join-Path $out "classes"
$dex = Join-Path $out "dex"

if (-not (Test-Path $Sdk)) { throw "Android SDK non trovato: $Sdk (usa -Sdk <percorso>)" }

$buildTools = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName "aapt2.exe") } |
    Sort-Object Name -Descending | Select-Object -First 1
$platform = Get-ChildItem (Join-Path $Sdk "platforms") -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools) { throw "build-tools incompleti in $Sdk (manca aapt2.exe): reinstalla le build-tools" }
if (-not $platform) { throw "platforms mancanti in $Sdk" }

$androidJar = Join-Path $platform.FullName "android.jar"
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
$d8 = Join-Path $buildTools.FullName "d8.bat"
$zipalign = Join-Path $buildTools.FullName "zipalign.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"

# JDK vero (javac nel PATH puo essere solo lo shim di javapath)
$jdkHome = $env:JAVA_HOME
if (-not $jdkHome -or -not (Test-Path (Join-Path $jdkHome "bin\keytool.exe"))) {
    $jdkHome = Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk-*" |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdkHome) { throw "JDK non trovato (serve keytool/jar)" }
$javac = Join-Path $jdkHome "bin\javac.exe"
$keytool = Join-Path $jdkHome "bin\keytool.exe"
$java = Join-Path $jdkHome "bin\java.exe"

Write-Host "sdk        $Sdk"
Write-Host "build-tools $($buildTools.Name)"
Write-Host "platform    $($platform.Name)"
Write-Host "jdk         $jdkHome"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $gen, $classes, $dex | Out-Null

# 1. risorse (se ci sono) + link del manifest -> R.java + APK senza dex
$linkArgs = @(
    "link",
    "-o", (Join-Path $out "base.apk"),
    "-I", $androidJar,
    "--manifest", (Join-Path $root "AndroidManifest.xml"),
    "--java", $gen,
    "--min-sdk-version", $MinSdk,
    "--target-sdk-version", $TargetSdk,
    "--auto-add-overlay"
)
$resDir = Join-Path $root "res"
if (Test-Path $resDir) {
    $compiled = Join-Path $out "res.zip"
    & $aapt2 compile --dir $resDir -o $compiled
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile fallito" }
    $linkArgs += @("-R", $compiled)
}
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link fallito" }

# 2. java -> class
$sources = @(Get-ChildItem $gen -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem (Join-Path $root "src") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
& $javac --release 11 -encoding UTF-8 -classpath $androidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac fallito" }

# 3. class -> dex
# Il d8 incluso nelle build-tools 34 (R8 8.2.2) crasha con NPE sui class file
# prodotti da JDK recenti, quindi usiamo un R8 aggiornato preso da Maven (una
# volta sola, ~20 MB, in .tools/) e teniamo d8.bat solo come ripiego.
$classFiles = @(Get-ChildItem $classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName })
$r8Version = "9.5.33"
$r8Dir = $toolsDir
$r8Jar = Join-Path $r8Dir "r8-$r8Version.jar"
if (-not (Test-Path $r8Jar)) {
    New-Item -ItemType Directory -Force -Path $r8Dir | Out-Null
    $r8Url = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$r8Version/r8-$r8Version.jar"
    Write-Host "scarico R8 $r8Version..."
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $r8Url -OutFile $r8Jar -UseBasicParsing
    } catch {
        Write-Warning "download R8 fallito ($($_.Exception.Message)): uso il d8 delle build-tools"
        Remove-Item $r8Jar -Force -ErrorAction SilentlyContinue
    }
}
if (Test-Path $r8Jar) {
    & $java -cp $r8Jar com.android.tools.r8.D8 --lib $androidJar --min-api $MinSdk --output $dex @classFiles
} else {
    & $d8 --lib $androidJar --min-api $MinSdk --output $dex @classFiles
}
if ($LASTEXITCODE -ne 0) { throw "d8 fallito" }

# 4. dex dentro l'APK
$unsigned = Join-Path $out "unsigned.apk"
Copy-Item (Join-Path $out "base.apk") $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex "classes.dex"), "classes.dex", "Optimal") | Out-Null
$zip.Dispose()

# 5. allineamento + firma (keystore di debug generata al primo build)
$aligned = Join-Path $out "aligned.apk"
& $zipalign -p -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign fallito" }

$keystore = Join-Path $toolsDir "debug.keystore"
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool fallito" }
}

$final = Join-Path $out "CFCookie.apk"
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner fallito" }

# apksigner (JDK 24) scrive warning su stderr: senza abbassare temporaneamente
# ErrorActionPreference lo script terminerebbe con exit code 1 anche se va tutto bene.
$verifyPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $apksigner verify --print-certs $final
$verifyCode = $LASTEXITCODE
$ErrorActionPreference = $verifyPref
if ($verifyCode -ne 0) { throw "firma non valida" }
Write-Host ""
Write-Host "APK: $final"
