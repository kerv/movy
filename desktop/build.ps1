param([string]$Version = "")
$ErrorActionPreference = "Stop"
$repositoryPath = Split-Path $PSScriptRoot -Parent
$release = Get-Content (Join-Path $repositoryPath "release/version.json") -Raw | ConvertFrom-Json
if (!$Version) { $Version = $release.versionName }
if ($Version -notmatch '^\d+\.\d+(\.\d+)?$') { throw "Invalid release version" }
$publishPath = Join-Path $PSScriptRoot "dist/win-x64"
$distPath = Join-Path $PSScriptRoot "dist"
New-Item -ItemType Directory -Path $distPath -Force | Out-Null
# This exact directory contains only generated publish output, never user data.
if (Test-Path $publishPath) { Remove-Item -LiteralPath $publishPath -Recurse -Force }
dotnet publish (Join-Path $PSScriptRoot "Movy.Desktop/Movy.Desktop.csproj") -c Release -r win-x64 --self-contained true -p:Version=$Version -p:PublishSingleFile=false -o $publishPath
if ($LASTEXITCODE -ne 0) { throw "Windows publication failed" }
Copy-Item (Join-Path $PSScriptRoot "README.md") (Join-Path $publishPath "README.windows.txt")
$zipName = "movy-windows-v$Version-x64.zip"
$zipPath = Join-Path $distPath $zipName
Compress-Archive -Path (Join-Path $publishPath "*") -DestinationPath $zipPath -Force
$hash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
@{
    versionName=$Version; architecture="win-x64"; preview=$true; signed=$false; selfContained=$true
    zipUrl="https://kerv.github.io/movy/movy-windows-x64.zip"; sha256=$hash
    notes="Native Windows preview. Extract the ZIP and run Movy.exe. Microsoft Edge WebView2 Runtime is required for account and stream resolution. First Play asks to download/checksum-verify official mpv 0.41.0. No Windows auto-installer or automatic update installation yet."
} | ConvertTo-Json | Set-Content (Join-Path $distPath "windows-version.json") -Encoding utf8NoBOM
"$hash  $zipName" | Set-Content (Join-Path $distPath "SHA256SUMS-windows.txt") -Encoding ascii
Write-Host "Windows ZIP: $zipPath"
