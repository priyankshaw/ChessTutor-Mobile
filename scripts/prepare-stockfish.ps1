param([switch]$Refresh, [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
$ErrorActionPreference = 'Stop'
$mobileRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stockfishRoot = Join-Path $mobileRoot 'vendor\stockfish'
$archive = Join-Path $stockfishRoot 'stockfish-android-arm64-universal.tar.gz'
$engine = Join-Path $stockfishRoot 'stockfish\stockfish-android-arm64-universal'
New-Item -ItemType Directory -Force -Path $stockfishRoot | Out-Null
if ($Refresh -or !(Test-Path -LiteralPath $archive)) {
  Invoke-WebRequest -Uri 'https://github.com/official-stockfish/Stockfish/releases/download/sf_19/stockfish-android-arm64-universal.tar.gz' -OutFile $archive
}
$archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
if ($archiveHash -ne 'EBB24051AA4A222B4DAAF049B882ECF1163D370C128FE02316602643F4D5E426') {
  throw 'Official Stockfish archive checksum changed. Review the source release before packaging.'
}
if ($Refresh -or !(Test-Path -LiteralPath $engine)) {
  & tar -xzf $archive -C $stockfishRoot
  if ($LASTEXITCODE -ne 0) { throw 'Could not extract official Stockfish archive.' }
}
$libraryDir = Join-Path $mobileRoot 'native-android\app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $libraryDir | Out-Null
$licenseDir = Join-Path $mobileRoot 'native-android\app\src\main\assets\licenses'
New-Item -ItemType Directory -Force -Path $licenseDir | Out-Null
Copy-Item -LiteralPath (Join-Path $stockfishRoot 'stockfish\Copying.txt') -Destination (Join-Path $licenseDir 'Stockfish-GPL-3.0.txt')
& $PSScriptRoot\prepare-native-engine.ps1 -Abi arm64-v8a -AndroidSdk $AndroidSdk
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
