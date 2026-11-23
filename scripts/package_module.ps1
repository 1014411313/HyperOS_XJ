param(
    [string]$Version = "3.1.0",
    [string]$GradleHome = "D:\gradle"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = $GradleHome

& python (Join-Path $root "scripts\doctor.py") --profile build
if ($LASTEXITCODE -ne 0) { throw "Build/signing precheck failed" }

Push-Location (Join-Path $root "ToolboxApp")
try {
    .\gradlew.bat :androidApp:assembleDebug --offline --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

& python (Join-Path $root "scripts\doctor.py") --profile build
if ($LASTEXITCODE -ne 0) { throw "Built APK signature check failed" }

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

$moduleProp = Join-Path $moduleDir "module.prop"
$moduleVersion = (Get-Content $moduleProp | Where-Object { $_ -match '^version=' } | Select-Object -First 1) -replace '^version=', ''
if ($moduleVersion -ne $Version) { throw "module.prop version mismatch: $moduleVersion" }
$apks = @(Get-ChildItem $moduleDir -Recurse -File -Filter *.apk)
if ($apks.Count -ne 1 -or $apks[0].FullName -ne (Join-Path $moduleDir "app\rezhale_toolbox.apk")) {
    throw "APK count or path mismatch"
}
$updateBinary = Join-Path $moduleDir "META-INF\com\google\android\update-binary"
if (-not (Test-Path -LiteralPath $updateBinary)) { throw "update-binary missing" }

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
$expected = @(
    Get-ChildItem $moduleDir -Recurse -File | ForEach-Object {
        $_.FullName.Substring($moduleDir.Length + 1).Replace('\', '/')
    } | Sort-Object
)
$entries = @($z.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
$actual = @($entries | ForEach-Object { $_.FullName } | Sort-Object)
if (($actual -join "`n") -ne ($expected -join "`n")) {
    $z.Dispose()
    throw "zip content differs from module tree"
}
if (($actual | Select-Object -Unique).Count -ne $actual.Count) {
    $z.Dispose()
    throw "zip has duplicate entries"
}
foreach ($entry in $entries) {
    if ($entry.FullName.StartsWith('/') -or $entry.FullName.Split('/') -contains '..') {
        $z.Dispose()
        throw "zip contains an unsafe path: $($entry.FullName)"
    }
    $stream = $entry.Open()
    try {
        $stream.CopyTo([System.IO.Stream]::Null)
    } finally {
        $stream.Dispose()
    }
}
$apkEntries = @($actual | Where-Object { $_.ToLowerInvariant().EndsWith('.apk') })
if ($apkEntries.Count -ne 1 -or $apkEntries[0] -ne 'app/rezhale_toolbox.apk') {
    $z.Dispose()
    throw "zip APK count or path mismatch"
}
$entry = $z.GetEntry('META-INF/com/google/android/update-binary')
if (-not $entry -or (($entry.ExternalAttributes -shr 16) -band 0x1ff) -ne 0x1ed) {
    $z.Dispose()
    throw "update-binary mode is not 0755"
}
$z.Dispose()

Write-Host "OK: $zip"
