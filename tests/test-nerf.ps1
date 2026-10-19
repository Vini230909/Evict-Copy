# Run Nerf regression checks against the built plugin in a fresh, disposable working directory.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = (Get-ChildItem "$env:USERPROFILE/.vscode/extensions/redhat.java-*/jre/*" | Select-Object -First 1).FullName
}
$gradleCache = $env:GRADLE_USER_HOME
if (-not $gradleCache) { $gradleCache = "$env:USERPROFILE/.gradle" }
$dependency = (Get-ChildItem "$gradleCache/caches/modules-2/files-2.1/Anuken/Mindustry/v157.4" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$testRoot = Join-Path $repo "build/nerf-tests/$([guid]::NewGuid())"
New-Item -ItemType Directory -Force "$testRoot/classes" | Out-Null
$classpath = "$repo/build/classes/java/main;$dependency"
& "$jdk/bin/javac.exe" -encoding UTF-8 -sourcepath "$testRoot/classes" -cp $classpath -d "$testRoot/classes" "$PSScriptRoot/NerfTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
function Invoke-NerfChecks([string] $fixture, [string[]] $testArguments) {
    $directory = Join-Path $testRoot $fixture
    New-Item -ItemType Directory -Force $directory | Out-Null
    Push-Location $directory
    try {
        & "$jdk/bin/java.exe" -cp "$testRoot/classes;$classpath" Extinction.NerfTest @testArguments
        if ($LASTEXITCODE -ne 0) { throw "$fixture Nerf checks failed." }
    } finally {
        Pop-Location
    }
}
Invoke-NerfChecks 'extinction' @()
Invoke-NerfChecks 'pure' @('pure-training')
Invoke-NerfChecks 'extinction-client' @('client', "$testRoot/extinction/training-nerf.patches")
Invoke-NerfChecks 'pure-client' @('client', "$testRoot/pure/pure-training-nerf.patches")
