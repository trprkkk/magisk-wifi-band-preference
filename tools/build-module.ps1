param(
    [string]$OutputDir
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleDir = Join-Path $repoRoot 'module'

if (-not (Test-Path $moduleDir)) {
    throw "module directory not found: $moduleDir"
}

# Read id / version from module.prop
$prop = @{}
Get-Content (Join-Path $moduleDir 'module.prop') | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') {
        $prop[$matches[1]] = $matches[2]
    }
}

$id = $prop['id']
$version = $prop['version']
if (-not $id -or -not $version) {
    throw 'module.prop must define id and version'
}

if (-not $OutputDir) {
    $OutputDir = Join-Path $repoRoot 'dist'
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$zipPath = Join-Path $OutputDir "$id-v$version.zip"
if (Test-Path $zipPath) {
    Remove-Item $zipPath
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Installer script (LF only)
$updateBinaryPath = Join-Path $PSScriptRoot 'update-binary.sh'
$updateBinary = [System.IO.File]::ReadAllText($updateBinaryPath).Replace("`r`n", "`n")

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zipStream = [System.IO.File]::Open($zipPath, 'Create')
$archive = New-Object System.IO.Compression.ZipArchive($zipStream, 'Create')

function Add-ZipEntry {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [string]$Name,
        [byte[]]$Bytes
    )

    $entry = $Archive.CreateEntry($Name)
    $stream = $entry.Open()
    $stream.Write($Bytes, 0, $Bytes.Length)
    $stream.Close()
}

# Module files (zip entries must use '/' separators)
Get-ChildItem $moduleDir -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($moduleDir.Length + 1) -replace '\\', '/'
    Add-ZipEntry -Archive $archive -Name $relative -Bytes ([System.IO.File]::ReadAllBytes($_.FullName))
}

Add-ZipEntry -Archive $archive -Name 'META-INF/com/google/android/update-binary' -Bytes $utf8NoBom.GetBytes($updateBinary)
Add-ZipEntry -Archive $archive -Name 'META-INF/com/google/android/updater-script' -Bytes $utf8NoBom.GetBytes('#MAGISK')

$archive.Dispose()
$zipStream.Close()

Write-Host "built: $zipPath"
