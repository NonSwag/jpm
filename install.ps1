# Installs the native jpm binary (and jvm.exe, a copy of it) into %USERPROFILE%\.jpm\bin.
#
#   irm https://github.com/NonSwag/jpm/releases/latest/download/install.ps1 | iex
#
# $env:JPM_VERSION picks a version, $env:JPM_HOME changes the install root, and $env:JPM_INSTALL_BASE
# replaces the download URL. Nothing outside JPM_HOME is changed.
$ErrorActionPreference = 'Stop'

$repo = if ($env:JPM_REPO) { $env:JPM_REPO } else { 'NonSwag/jpm' }
if ($env:JPM_INSTALL_BASE) { $base = $env:JPM_INSTALL_BASE }
elseif ($env:JPM_VERSION) { $base = "https://github.com/$repo/releases/download/v$($env:JPM_VERSION)" }
else { $base = "https://github.com/$repo/releases/latest/download" }

$jpmHome = if ($env:JPM_HOME) { $env:JPM_HOME } else { Join-Path $env:USERPROFILE '.jpm' }
$dir = Join-Path $jpmHome 'bin'
$asset = 'jpm-windows-x64.exe'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("jpm-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $tmp | Out-Null

try {
    Write-Host "Downloading $asset from $base"
    Invoke-WebRequest "$base/$asset" -OutFile (Join-Path $tmp 'jpm.exe') -UseBasicParsing
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS') -UseBasicParsing

    $expected = $null
    foreach ($line in Get-Content (Join-Path $tmp 'SHA256SUMS')) {
        $parts = $line -split '\s+'
        if ($parts.Length -ge 2 -and ($parts[1] -eq $asset -or $parts[1] -eq "*$asset")) { $expected = $parts[0].ToLower() }
    }
    $actual = (Get-FileHash (Join-Path $tmp 'jpm.exe') -Algorithm SHA256).Hash.ToLower()
    if (-not $expected -or $expected -ne $actual) {
        throw "checksum mismatch for $asset (expected $expected, got $actual)"
    }

    New-Item -ItemType Directory -Force $dir | Out-Null
    Move-Item -Force (Join-Path $tmp 'jpm.exe') (Join-Path $dir 'jpm.exe')
    Copy-Item -Force (Join-Path $dir 'jpm.exe') (Join-Path $dir 'jvm.exe')
    $version = & (Join-Path $dir 'jpm.exe') --version
    Write-Host "Installed $version to $dir (with jvm.exe)"
    if (-not (($env:PATH -split ';') -contains $dir)) {
        Write-Host "Add it to your PATH:  [Environment]::SetEnvironmentVariable('PATH', `"$dir;`" + [Environment]::GetEnvironmentVariable('PATH', 'User'), 'User')"
    }
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
