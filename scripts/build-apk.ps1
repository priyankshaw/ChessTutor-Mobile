param([string]$AndroidSdk, [string]$Python, [switch]$SyncDesktop)
$ErrorActionPreference = 'Stop'
$mobileRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $mobileRoot
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$Python) { $Python = Join-Path $mobileRoot '.venv\Scripts\python.exe' }
if (!(Test-Path -LiteralPath $AndroidSdk)) { throw 'Install Android SDK or pass -AndroidSdk.' }
if (!(Test-Path -LiteralPath $Python)) { throw 'Create Python 3.12 .venv or pass -Python.' }
$env:ANDROID_HOME = [IO.Path]::GetFullPath($AndroidSdk)
$env:CHESSTUTOR_BUILD_PYTHON = [IO.Path]::GetFullPath($Python)
$env:GRADLE_USER_HOME = Join-Path $mobileRoot '.g'
if ($SyncDesktop) {
    & $Python scripts\sync-upstream.py
    if ($LASTEXITCODE -ne 0) { throw 'Desktop snapshot failed; previous algorithms retained.' }
}
& $PSScriptRoot\prepare-stockfish.ps1 -AndroidSdk $AndroidSdk
Copy-Item -LiteralPath 'THIRD_PARTY_NOTICES.md' -Destination 'native-android\app\src\main\assets\licenses\THIRD_PARTY_NOTICES.md'
& $Python scripts\test-desktop-parity.py
if ($LASTEXITCODE -ne 0) { throw 'Desktop coaching regressions failed.' }
& $Python -m unittest tests.test_runtime tests.test_book_index tests.test_upstream_snapshot
if ($LASTEXITCODE -ne 0) { throw 'Native runtime or book checks failed.' }
& .\native-android\gradlew.bat -p native-android :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation, native tests, lint or APK build failed.' }
$output = Join-Path $mobileRoot 'artifacts\native-release'
New-Item -ItemType Directory -Force -Path $output | Out-Null
Copy-Item -LiteralPath 'native-android\app\build\outputs\apk\release\app-release.apk' -Destination (Join-Path $output 'ChessTutor-native-arm64.apk')
Copy-Item -LiteralPath 'vendor\stockfish\stockfish-android-arm64-universal.tar.gz' -Destination (Join-Path $output 'Stockfish-19-corresponding-source.tar.gz')
& $Python scripts\verify-apk.py (Join-Path $output 'ChessTutor-native-arm64.apk')
if ($LASTEXITCODE -ne 0) { throw 'Native APK contents check failed.' }
$buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose (Join-Path $output 'ChessTutor-native-arm64.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK signature check failed.' }
& $Python scripts\package-source.py
if ($LASTEXITCODE -ne 0) { throw 'Corresponding source packaging failed.' }
Get-FileHash -LiteralPath (Join-Path $output 'ChessTutor-native-arm64.apk') -Algorithm SHA256 | Select-Object Hash
