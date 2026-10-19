# Exercise plugin startup, native patch loading, worker reuse and client world transfer in real servers.
param([string[]]$Versions = @('v157.4', 'v160.7'))
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$jdk = $env:JAVA_HOME
if (-not $jdk) {
    $jdk = (Get-ChildItem "$env:USERPROFILE/.vscode/extensions/redhat.java-*/jre/*" | Select-Object -First 1).FullName
}
$cache = Join-Path $repo '.gradle/nerf-server-cache'
$testRoot = Join-Path $repo "build/nerf-worker-tests/$([guid]::NewGuid())"
New-Item -ItemType Directory -Force $cache, $testRoot | Out-Null
$plugin = Join-Path $repo 'build/libs/EvictMapGenerator.jar'
if (-not (Test-Path $plugin)) { throw 'Build the plugin jar first.' }
$helper = Join-Path $testRoot 'Provision.java'
@'
import java.io.File;
public class Provision {
    public static void main(String[] args) throws Exception {
        Extinction.Nerf.provision(new File(args[0]), Boolean.parseBoolean(args[1]));
    }
}
'@ | Set-Content -LiteralPath $helper

function Invoke-Server([string]$server, [string]$folder, [string[]]$commands, [bool]$worker = $true) {
    New-Item -ItemType Directory -Force $folder | Out-Null
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = "$jdk/bin/java.exe"
    $start.ArgumentList.Add('--enable-native-access=ALL-UNNAMED')
    if ($worker) { $start.ArgumentList.Add('-Devict.duelWorker=true') }
    $start.ArgumentList.Add('-jar')
    $start.ArgumentList.Add($server)
    $start.WorkingDirectory = $folder
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        foreach ($command in $commands) { $process.StandardInput.WriteLine($command) }
        $process.StandardInput.WriteLine('exit')
        if (-not $process.WaitForExit(50000)) { $process.Kill(); throw 'Dedicated server timed out.' }
        $log = $stdout.Result + $stderr.Result
        $log | Set-Content -LiteralPath (Join-Path $folder 'integration.log')
        if ($process.ExitCode -ne 0 -or $log -match '\[E\]|NoClassDefFoundError|Exception') {
            throw "Dedicated server failed in $folder`n$log"
        }
        return $log
    } finally {
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}

$snapshot = 'js (function(){var n;try{n=Vars.state.patcher.patches.size;}catch(e){n=Vars.state.data.getPatches().size;}var r=[];for(var i=0;i<Blocks.overdriveDome.requirements.length;i++){r.push(Blocks.overdriveDome.requirements[i].item.name+"/"+Blocks.overdriveDome.requirements[i].amount);}Log.info("__NERF_RESULT__"+JSON.stringify({patches:n,dome:Blocks.overdriveDome.speedBoost,power:Blocks.overdriveDome.consPower.usage,requirements:r.join(","),projector:Blocks.overdriveProjector.speedBoost,quasar:UnitTypes.quasar.health,navanax0:UnitTypes.navanax.weapons.get(0).bullet.timeIncrease,navanax1:UnitTypes.navanax.weapons.get(1).bullet.timeIncrease}));})()'
$transfer = 'js (function(){var p=Player.create();p.team(Team.sharded);Packages.mindustry.net.NetworkIO.writeWorld(p,new java.io.FileOutputStream("world.bin"));if(Version.build>=159){Packages.mindustry.net.NetworkIO.writeAssets(new java.io.FileOutputStream("assets.bin"),Vars.state.data.getAllExternalAssets());}Log.info("__WORLD_WRITTEN__");})()'
$receive = 'js (function(){Vars.player=Player.create();if(Version.build>=159){Packages.mindustry.net.NetworkIO.loadAssets(new java.io.FileInputStream("assets.bin"));}Packages.mindustry.net.NetworkIO.loadWorld(new java.io.FileInputStream("world.bin"));Log.info("__WORLD_LOADED__");})()'

function Assert-Snapshot([string]$log, [bool]$nerf) {
    if ($log -notmatch '__NERF_RESULT__(\{[^\r\n]+\})') { throw "No live content snapshot.`n$log" }
    $value = $Matches[1] | ConvertFrom-Json
    if ($nerf) {
        if ($value.patches -ne 1 -or $value.dome -ne 1.5 -or $value.projector -ne 1.25 -or
            $value.power -ne 25 -or $value.quasar -ne 500 -or $value.navanax0 -ne 2 -or
            $value.navanax1 -ne 2 -or $value.requirements -notmatch 'phase-fabric/20') {
            throw "Live Nerf values do not match: $($value | ConvertTo-Json -Compress)"
        }
    } elseif ($value.patches -ne 0 -or $value.dome -eq 1.5 -or $value.quasar -eq 500) {
        throw "Plain reused worker retained Nerf: $($value | ConvertTo-Json -Compress)"
    }
}

foreach ($version in $Versions) {
    $server = Join-Path $cache "server-$version.jar"
    if (-not (Test-Path $server)) {
        Invoke-WebRequest -Uri "https://github.com/Anuken/Mindustry/releases/download/$version/server-release.jar" -OutFile $server
    }
    $versionRoot = Join-Path $testRoot $version
    $fixture = Join-Path $versionRoot 'fixture'
    # Generate a real map without a plugin or patch; large enough for the Extinction generator.
    $makeMap = 'js (function(){Vars.world.loadGenerator(1024,1024,cons(function(t){t.fill();for(var i=0;i<1024*1024;i++){t.geti(i).setFloor(Blocks.stone);}t.get(400,400).setBlock(Blocks.coreShard,Team.sharded,0);t.get(600,600).setBlock(Blocks.coreShard,Team.crux,0);}));var tags=new StringMap();tags.put("name","integration");if(Version.build>=159){var options=new Packages.mindustry.io.SaveOptions();options.extraTags=tags;SaveIO.write(new Fi("config/maps/integration.msav"),options);}else{SaveIO.write(new Fi("config/maps/integration.msav"),tags);}Log.info("__MAP_WRITTEN__");})()'
    $fixtureLog = Invoke-Server $server $fixture @('config port 16571', 'host Fork pvp', $makeMap) $false
    if ($fixtureLog -notmatch '__MAP_WRITTEN__') { throw 'Could not generate fixture map.' }
    foreach ($mode in @('pure-training', 'training')) {
        $worker = Join-Path $versionRoot $mode
        $config = Join-Path $worker 'config'
        New-Item -ItemType Directory -Force "$config/mods", "$config/maps" | Out-Null
        Copy-Item -LiteralPath $plugin -Destination "$config/mods/EvictMapGenerator.jar"
        Copy-Item -LiteralPath "$fixture/config/maps/integration.msav" -Destination "$config/maps/integration.msav"
        foreach ($pass in @('nerf', 'nerf-reused', 'plain')) {
            $nerf = $pass -ne 'plain'
            & "$jdk/bin/java.exe" --class-path $plugin $helper $config $nerf.ToString()
            if ($LASTEXITCODE -ne 0) { throw 'Worker Nerf provisioning failed.' }
            @("mode=$mode", "patch=$(if ($nerf) { 'nerf' } else { 'none' })", 'map=integration',
                'label=Integration test', 'team.count=1', 'team.1=integration-test-player',
                'name.integration-test-player=Tester', 'hub.ip=127.0.0.1', 'hub.port=6567') |
                Set-Content -LiteralPath "$worker/duel.properties"
            if (-not $nerf -and ((Test-Path "$config/patches/extinction-nerf.hjson") -or
                (Test-Path "$config/assets/patches/extinction-nerf.hjson"))) {
                throw 'Plain worker retained a managed patch file.'
            }
            $commands = @('config port 16572')
            if ($mode -eq 'pure-training') { $commands += 'oregen auto off' }
            $commands += @('host integration pvp', $snapshot, $transfer)
            $log = Invoke-Server $server $worker $commands
            Assert-Snapshot $log $nerf
            if ($log -notmatch '__WORLD_WRITTEN__') { throw 'Native world transfer was not written.' }
            Copy-Item -LiteralPath "$worker/integration.log" -Destination "$worker/$pass.log"
            $client = Join-Path $worker "client-$pass"
            New-Item -ItemType Directory -Force $client | Out-Null
            Copy-Item -LiteralPath "$worker/world.bin" -Destination "$client/world.bin"
            if (Test-Path "$worker/assets.bin") { Copy-Item -LiteralPath "$worker/assets.bin" -Destination "$client/assets.bin" }
            $clientLog = Invoke-Server $server $client @($receive, $snapshot) $false
            Assert-Snapshot $clientLog $nerf
            if ($clientLog -notmatch '__WORLD_LOADED__') { throw 'Fresh native client did not load the transferred world.' }
            Write-Output "PASS: $version $mode $pass startup, live balance and fresh native client transfer"
        }
    }
}
Write-Output "Logs: $testRoot"
