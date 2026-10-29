param([switch]$TestSync)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $projectRoot '.tools'
$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $toolsDir 'jdk17') -Directory | Select-Object -First 1).FullName
if (-not $env:JAVA_HOME) { throw 'Install JDK 17 under .tools/jdk17 first.' }
$env:ANDROID_HOME = Join-Path $toolsDir 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $toolsDir 'gradle-cache'
$gradle = Join-Path $toolsDir 'gradle/gradle-8.13/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
$tasks = @(':app:assembleWhiteDebug', ':app:assembleChampionDebug')
if ($TestSync) { $tasks += ':app:testWhiteDebugUnitTest' }
& $gradle -p $PSScriptRoot @tasks --console=plain --max-workers=2
if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." }
$output = Join-Path $projectRoot 'releases'
New-Item -ItemType Directory -Force -Path $output | Out-Null
foreach ($role in @('white', 'champion')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "app/build/outputs/apk/$role/debug/app-$role-debug.apk") `
        -Destination (Join-Path $output "love-gaze-$role-debug.apk")
}
Write-Output "APKs: $output"
