$RepoRoot = Split-Path -Parent $PSScriptRoot

$JavaHome = Join-Path $RepoRoot ".toolchain\java\jdk-21.0.11+10"
$MavenHome = Join-Path $RepoRoot ".toolchain\apache-maven-3.9.9"
$DockerBin = Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\resources\bin"

if (-not (Test-Path $JavaHome)) {
    throw "Java toolchain not found at $JavaHome"
}

if (-not (Test-Path $MavenHome)) {
    throw "Maven toolchain not found at $MavenHome"
}

$env:JAVA_HOME = $JavaHome
$env:MAVEN_HOME = $MavenHome
$PathPrefix = "$JavaHome\bin;$MavenHome\bin"

if (Test-Path $DockerBin) {
    $PathPrefix = "$PathPrefix;$DockerBin"
}

$env:Path = "$PathPrefix;$env:Path"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "MAVEN_HOME=$env:MAVEN_HOME"
if (Test-Path $DockerBin) {
    Write-Host "DOCKER_BIN=$DockerBin"
}
java -version
mvn -version
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker --version
    docker compose version
}
