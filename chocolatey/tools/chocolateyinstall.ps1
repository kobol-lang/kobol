$ErrorActionPreference = 'Stop'

$packageName = 'kobol'
$version     = $env:ChocolateyPackageVersion   # set by Chocolatey from the .nuspec
$toolsDir    = Split-Path -Parent $MyInvocation.MyCommand.Definition
$url64       = "https://github.com/kobol-lang/kobol/releases/download/v$version/kobol-windows-x86_64.zip"

$packageArgs = @{
  packageName    = $packageName
  unzipLocation  = $toolsDir
  url64bit       = $url64
  # Replace with the real digest from the release's SHA256SUMS.txt
  # (the `chocolatey` job in .github/workflows/release.yml does this automatically).
  checksum64     = 'PLACEHOLDER_WINDOWS_X86_64_SHA256'
  checksumType64 = 'sha256'
}

# Downloads, verifies the checksum, unzips, and auto-shims kobol.exe onto PATH.
Install-ChocolateyZipPackage @packageArgs
