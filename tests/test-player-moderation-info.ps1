# Run /info moderation privacy checks against the built plugin with delayed database callbacks.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = (Get-ChildItem "$env:USERPROFILE/.vscode/extensions/redhat.java-*/jre/*" | Select-Object -First 1).FullName
}
$gradleCache = $env:GRADLE_USER_HOME
if (-not $gradleCache) { $gradleCache = "$env:USERPROFILE/.gradle" }
$dependency = (Get-ChildItem "$gradleCache/caches/modules-2/files-2.1/Anuken/Mindustry/v157.4" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$testRoot = Join-Path $repo "build/player-moderation-info-tests/$([guid]::NewGuid())"
New-Item -ItemType Directory -Force "$testRoot/classes" | Out-Null
$classpath = "$repo/build/classes/java/main;$repo/build/libs/EvictMapGenerator.jar;$dependency"
& "$jdk/bin/javac.exe" -encoding UTF-8 -sourcepath "$testRoot/classes" -cp $classpath -d "$testRoot/classes" "$PSScriptRoot/PlayerModerationInfoTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
Push-Location $testRoot
try {
    & "$jdk/bin/java.exe" -cp "$testRoot/classes;$classpath" Extinction.PlayerModerationInfoTest
    if ($LASTEXITCODE -ne 0) { throw 'Player moderation info checks failed.' }
} finally {
    Pop-Location
}
