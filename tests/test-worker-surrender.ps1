# Run worker surrender regression checks against the built plugin in a fresh, disposable working directory.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = (Get-ChildItem "$env:USERPROFILE/.vscode/extensions/redhat.java-*/jre/*" | Select-Object -First 1).FullName
}
$gradleCache = $env:GRADLE_USER_HOME
if (-not $gradleCache) { $gradleCache = "$env:USERPROFILE/.gradle" }
$dependency = (Get-ChildItem "$gradleCache/caches/modules-2/files-2.1/Anuken/Mindustry/v157.4" -Recurse -Filter '*.jar' | Select-Object -First 1).FullName
$testRoot = Join-Path $repo "build/worker-surrender-tests/$([guid]::NewGuid())"
New-Item -ItemType Directory -Force "$testRoot/classes" | Out-Null
$classpath = "$repo/build/classes/java/main;$dependency"
& "$jdk/bin/javac.exe" -encoding UTF-8 -sourcepath "$testRoot/classes" -cp $classpath -d "$testRoot/classes" "$PSScriptRoot/WorkerSurrenderTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
Push-Location $testRoot
try {
    & "$jdk/bin/java.exe" -cp "$testRoot/classes;$classpath" Extinction.WorkerSurrenderTest
    if ($LASTEXITCODE -ne 0) { throw 'Worker surrender checks failed.' }
} finally {
    Pop-Location
}
