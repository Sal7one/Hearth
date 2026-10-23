$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$build = Join-Path $repo 'build/desktop-media'
cmake -S "$repo/desktop-media/src/main/cpp" -B $build -A x64
if ($LASTEXITCODE -ne 0) { throw 'Media configuration failed' }
cmake --build $build --config Release --parallel 2
if ($LASTEXITCODE -ne 0) { throw 'Media build failed' }
ctest --test-dir $build -C Release --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Media host tests failed' }
$stage = Join-Path $repo 'desktop-media/native-libs/windows-x64'
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$build/Release/hearth-media-decode.exe" $stage -Force
