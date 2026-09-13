param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $folioJdk = Get-ChildItem -LiteralPath "$PSScriptRoot/.tooling/jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($folioJdk) { $env:JAVA_HOME = $folioJdk.FullName }
    if (Test-Path -LiteralPath "$PSScriptRoot/.tooling/gradle-home") { $env:GRADLE_USER_HOME = "$PSScriptRoot/.tooling/gradle-home" }
    & "$PSScriptRoot/gradlew.bat" @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
