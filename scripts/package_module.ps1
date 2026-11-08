param(
    [string]$Version = "3.1.0",
    [string]$GradleHome = "D:\gradle"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = $GradleHome

Push-Location (Join-Path $root "ToolboxApp")
try {
    .\gradlew.bat :androidApp:assembleDebug --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

$apk = Join-Path $root "ToolboxApp\androidApp\build\outputs\apk\debug\androidApp-debug.apk"
$appDir = Join-Path $root "dist\HyperOS_XJ_New\app"
New-Item -ItemType Directory -Force -Path $appDir | Out-Null
Copy-Item -Force $apk (Join-Path $appDir "rezhale_toolbox.apk")

# 根 engine/ 是唯一编辑源；打包前同步到模块树，避免两份漂移。
$engineSrc = Join-Path $root "engine"
$engineDst = Join-Path $root "dist\HyperOS_XJ_New\engine"
if (Test-Path -LiteralPath $engineDst) { Remove-Item -LiteralPath $engineDst -Recurse -Force }
Copy-Item -Recurse -Force $engineSrc $engineDst

$bash = (Get-Command bash -ErrorAction SilentlyContinue).Source
if (-not $bash) { $bash = "C:\Program Files\Git\bin\bash.exe" }
if (-not (Test-Path $bash)) { throw "bash not found; install Git Bash or pass a working bash" }

$moduleDir = Join-Path $root "dist\HyperOS_XJ_New"
$fail = 0
Get-ChildItem $moduleDir -Recurse -Filter *.sh | ForEach-Object {
    & $bash -n $_.FullName 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "SYNTAX FAIL $($_.FullName)"
        $fail = 1
    }
}
if ($fail -ne 0) { throw "syntax check failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = Join-Path $root "dist\热炸了工具箱_v$Version.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $moduleDir, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $false)

$z = [System.IO.Compression.ZipFile]::Open($zip, 'Update')
$execAttr = -2115174400   # 0x81ED0000 -> 0755
$fileAttr = -2119958528   # 0x81A40000 -> 0644
foreach ($e in $z.Entries) {
    $n = $e.FullName
    $exec = ($n -match '\.sh$') -or ($n -eq 'META-INF/com/google/android/update-binary')
    if ($exec) { $e.ExternalAttributes = $execAttr } else { $e.ExternalAttributes = $fileAttr }
}
$z.Dispose()

Write-Host "OK: $zip"
