param([string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), [ValidateSet('x86_64','arm64-v8a')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$mobileRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = Join-Path $mobileRoot 'vendor\stockfish\stockfish\src'
$buildRoot = Join-Path $mobileRoot "vendor\stockfish-native\$Abi"
$ndk = Get-ChildItem (Join-Path $AndroidSdk 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$cmake = Get-ChildItem (Join-Path $AndroidSdk 'cmake') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (!$ndk -or !$cmake) { throw 'Install Android NDK 27+ and CMake to build the 16 KB-compatible engine.' }
New-Item -ItemType Directory -Force -Path $buildRoot,(Join-Path $buildRoot 'build') | Out-Null
$networkName = [regex]::Match((Get-Content (Join-Path $sourceRoot 'evaluate.h') -Raw), 'EvalFileDefaultName "(.*?)"').Groups[1].Value
$network = Join-Path $buildRoot "build\$networkName"
if (!(Test-Path -LiteralPath $network)) {
    $cachedNetwork = @("vendor\stockfish-emulator\build\$networkName", "third-party\stockfish\$networkName") | ForEach-Object { Join-Path $mobileRoot $_ } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($cachedNetwork) { Copy-Item -LiteralPath $cachedNetwork -Destination $network }
    else { Invoke-WebRequest -Uri "https://tests.stockfishchess.org/api/nn/$networkName" -OutFile $network }
}
$expectedPrefix = [regex]::Match($networkName, 'nn-([a-f0-9]+)').Groups[1].Value
if (!(Get-FileHash $network -Algorithm SHA256).Hash.ToLowerInvariant().StartsWith($expectedPrefix)) { throw 'Stockfish NNUE checksum failed.' }
$makefile = Get-Content (Join-Path $sourceRoot 'Makefile') -Raw
$sources = [regex]::Match($makefile, '(?ms)^SRCS = (.*?)\r?\n\r?\n').Groups[1].Value.Replace('\','') -split '\s+' | Where-Object { $_ }
$sourceFiles = ($sources | ForEach-Object { '"' + (Join-Path $sourceRoot $_).Replace('\','/') + '"' }) -join "`n"
$simd = if ($Abi -eq 'arm64-v8a') { 'USE_NEON' } else { 'USE_SSE2' }
$cpuFlag = if ($Abi -eq 'arm64-v8a') { '-march=armv8-a' } else { '-msse2' }
@"
cmake_minimum_required(VERSION 3.22)
project(StockfishEmulator LANGUAGES CXX)
add_executable(stockfish
$sourceFiles
)
target_compile_features(stockfish PRIVATE cxx_std_17)
target_compile_definitions(stockfish PRIVATE IS_64BIT $simd NDEBUG)
target_compile_options(stockfish PRIVATE -O3 -fno-exceptions $cpuFlag)
target_link_options(stockfish PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
target_link_libraries(stockfish PRIVATE atomic)
"@ | Set-Content -LiteralPath (Join-Path $buildRoot 'CMakeLists.txt')
& (Join-Path $cmake.FullName 'bin\cmake.exe') -S $buildRoot -B (Join-Path $buildRoot 'build') -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$($ndk.FullName)/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$Abi" '-DANDROID_PLATFORM=android-31' '-DANDROID_STL=c++_static' "-DCMAKE_MAKE_PROGRAM=$($cmake.FullName)/bin/ninja.exe"
if ($LASTEXITCODE -ne 0) { throw 'Stockfish emulator configure failed.' }
& (Join-Path $cmake.FullName 'bin\cmake.exe') --build (Join-Path $buildRoot 'build') --parallel 4
if ($LASTEXITCODE -ne 0) { throw 'Stockfish emulator compilation failed.' }
$destination = Join-Path $mobileRoot "native-android\app\src\main\jniLibs\$Abi"
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $buildRoot 'build\stockfish') -Destination (Join-Path $destination 'libstockfish.so')
Write-Output "Stockfish 19 ready for $Abi, 16 KB memory pages, verified NNUE. Phone releases default to ARM64."
