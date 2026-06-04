$ErrorActionPreference = 'Stop'

$packageName = 'kobol'

# Removes the auto-created kobol.exe shim and the unzipped files.
Uninstall-ChocolateyZipPackage `
  -PackageName  $packageName `
  -ZipFileName  'kobol-windows-x86_64.zip'
