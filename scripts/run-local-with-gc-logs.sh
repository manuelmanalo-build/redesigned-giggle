#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

JAR_PATH="${JAR_PATH:-target/realtime-trade-processing-simulator-0.0.1-SNAPSHOT.jar}"
SPRING_PROFILE="${SPRING_PROFILE:-local}"
GC_LOG_DIR="${GC_LOG_DIR:-logs/gc}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$GC_LOG_DIR"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found at $JAR_PATH; building with ./mvnw -DskipTests package"
  ./mvnw -DskipTests package
fi

DEFAULT_JAVA_OPTS=(
  "-Xms256m"
  "-Xmx512m"
  "-XX:+UseG1GC"
  "-Xlog:gc*,safepoint:file=${GC_LOG_DIR}/gc-${TIMESTAMP}.log:time,uptime,level,tags:filecount=5,filesize=10m"
)

echo "Starting application with Spring profile: $SPRING_PROFILE"
echo "GC logs: ${GC_LOG_DIR}/gc-${TIMESTAMP}.log"
echo "Override demo JVM flags with EXTRA_JAVA_OPTS if needed."

# shellcheck disable=SC2206
EXTRA_OPTS=(${EXTRA_JAVA_OPTS:-})
exec java "${DEFAULT_JAVA_OPTS[@]}" "${EXTRA_OPTS[@]}" -Dspring.profiles.active="$SPRING_PROFILE" -jar "$JAR_PATH"
