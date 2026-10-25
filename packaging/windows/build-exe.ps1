<#
  Builds the native Windows EarthLife launcher (Windows 10/11, x64).

  Output (in dist\windows):
    EarthLife\EarthLife.exe           portable app folder: native .exe + bundled Java runtime (no Java install needed)
    EarthLife-0.3.0-portable.zip      the same folder zipped
    EarthLife-0.3.0.exe               installer with Start-menu + desktop shortcuts (only if WiX Toolset is installed)

  Requirements: JDK 21+ (e.g. Eclipse Temurin 21) on PATH or JAVA_HOME. For the installer: WiX Toolset 3.14
  (winget install WiXToolset.WiXToolset) - without WiX you still get the portable EarthLife.exe.

  Run from the repo root:  powershell -ExecutionPolicy Bypass -File packaging\windows\build-exe.ps1
#>
param([string]$Version = "0.3.0", [switch]$SkipTests)
$ErrorActionPreference = "Stop"
trap { Write-Host "BUILD ERROR: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"; exit 1 }
$Root = (Resolve-Path "$PSScriptRoot\..\..").Path
Set-Location $Root

function Find-Tool($name) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\$name.exe")) { return "$env:JAVA_HOME\bin\$name.exe" }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "$name not found. Install JDK 21+ (https://adoptium.net) and set JAVA_HOME."
}
$javac = Find-Tool javac; $java = Find-Tool java; $jar = Find-Tool jar; $jlink = Find-Tool jlink; $jpackage = Find-Tool jpackage
# "java --version" prints to stdout (no stderr redirection, which Windows PowerShell 5.1 turns into errors)
$verLine = (& $java --version | Select-Object -First 1)
$major = [int]([regex]::Match($verLine, '\s(\d+)[\.\s]').Groups[1].Value)
if ($major -lt 21) { throw "JDK 21+ required (found: $verLine)." }

$Out = "$Root\build\win"; $Dist = "$Root\dist\windows"
Remove-Item -Recurse -Force $Out, $Dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$Out\core", "$Out\launcher", "$Out\input", $Dist | Out-Null

Write-Host "== Compiling core + launcher"
# javac @argfiles avoid Windows' command-line length limit
function Write-Sources($dir, $file) {
    Get-ChildItem -Recurse $dir -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } | Set-Content -Encoding ASCII $file
}
Write-Sources "$Root\core\src\main\java" "$Out\core-src.txt"
& $javac --release 21 -encoding UTF-8 -d "$Out\core" "@$Out\core-src.txt"; if ($LASTEXITCODE) { throw "core compile failed" }
Write-Sources "$Root\launcher\src\main\java" "$Out\launcher-src.txt"
& $javac --release 21 -encoding UTF-8 -cp "$Out\core" -d "$Out\launcher" "@$Out\launcher-src.txt"; if ($LASTEXITCODE) { throw "launcher compile failed" }

if (-not $SkipTests) {
    Write-Host "== Running tests"
    New-Item -ItemType Directory -Force "$Out\core-test", "$Out\launcher-test" | Out-Null
    Write-Sources "$Root\core\src\test\java" "$Out\core-test-src.txt"
    & $javac -encoding UTF-8 -cp "$Out\core" -d "$Out\core-test" "@$Out\core-test-src.txt"
    Push-Location "$Root\core"; & $java "-Djava.awt.headless=true" "-Dearthlife.data=..\data" -cp "$Out\core;$Out\core-test" earthlife.test.T; $code = $LASTEXITCODE; Pop-Location
    if ($code) { throw "core tests failed" }
    Write-Sources "$Root\launcher\src\test\java" "$Out\launcher-test-src.txt"
    & $javac -encoding UTF-8 -cp "$Out\core;$Out\launcher" -d "$Out\launcher-test" "@$Out\launcher-test-src.txt"
    & $java -cp "$Out\core;$Out\launcher;$Out\launcher-test" earthlife.launcher.test.LauncherTest "$Root\launcher\src\test\resources"
    if ($LASTEXITCODE) { throw "launcher tests failed" }
}

Write-Host "== Packaging jar"
& $jar --create --file "$Out\input\earthlife-launcher.jar" --main-class earthlife.launcher.Main -C "$Out\launcher" . -C "$Out\core" .
if (Test-Path "$Root\earthlife-mod.jar") { Copy-Item "$Root\earthlife-mod.jar" "$Out\input\" }
elseif (Test-Path "$Root\fabric-mod\build\libs") { Get-ChildItem "$Root\fabric-mod\build\libs\earthlife-mod-*.jar" | Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1 | Copy-Item -Destination "$Out\input\earthlife-mod.jar" }

Write-Host "== Building minimal Java runtime (jlink)"
& $jlink --add-modules java.base,java.desktop,java.net.http,java.logging,jdk.crypto.ec --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output "$Out\runtime"
if ($LASTEXITCODE) { throw "jlink failed" }

$common = @("--name", "EarthLife", "--app-version", $Version, "--vendor", "EarthLife",
    "--description", "EarthLife - Reality Simulator launcher for Minecraft: Java Edition",
    "--input", "$Out\input", "--main-jar", "earthlife-launcher.jar", "--main-class", "earthlife.launcher.Main",
    "--runtime-image", "$Out\runtime", "--icon", "$PSScriptRoot\earthlife.ico",
    "--java-options", "-Xmx512m", "--java-options", "-Dsun.java2d.uiScale.enabled=true", "--dest", $Dist)

Write-Host "== jpackage: portable EarthLife.exe"
& $jpackage --type app-image @common
if ($LASTEXITCODE) { throw "jpackage app-image failed" }
Compress-Archive -Path "$Dist\EarthLife" -DestinationPath "$Dist\EarthLife-$Version-portable.zip" -Force

$wix = (Get-Command candle.exe -ErrorAction SilentlyContinue) -or (Get-Command wix.exe -ErrorAction SilentlyContinue) -or (Test-Path "${env:ProgramFiles(x86)}\WiX Toolset v3.14\bin\candle.exe")
if ($wix) {
    Write-Host "== jpackage: installer"
    & $jpackage --type exe @common --win-menu --win-menu-group "EarthLife" --win-shortcut --win-shortcut-prompt --win-dir-chooser `
        --win-per-user-install --win-upgrade-uuid "6f1e3b8e-2b7c-4c55-9a8e-5a2e1f0c3e01"
    if ($LASTEXITCODE) { throw "jpackage installer failed" }
} else {
    Write-Warning "WiX Toolset not found: skipped the installer. The portable EarthLife.exe is ready. (winget install WiXToolset.WiXToolset)"
}
Write-Host "Done:"; Get-ChildItem $Dist | Format-Table Name, Length
