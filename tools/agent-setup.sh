#!/usr/bin/env bash
# Подготовка облачного окружения агента (Codex и др.): JDK 17, xvfb и все
# зависимости сборки заранее — потом сборка и тесты идут и без интернета (-o).
set -euo pipefail

if ! command -v java >/dev/null || ! java -version 2>&1 | grep -q '"17'; then
    apt-get update -qq
    apt-get install -y -qq openjdk-17-jdk-headless
fi
if ! command -v xvfb-run >/dev/null; then
    apt-get update -qq
    apt-get install -y -qq xvfb
fi

export LANG=C.UTF-8
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
# Центральный репозиторий через прокси облака иногда отвечает 429 — зеркало.
export MVNW_REPOURL="${MVNW_REPOURL:-https://repo1.maven.org/maven2}"

cd "$(dirname "$0")/.."
# Скачивает Maven, плагины Tycho и target platform Eclipse 2023-12 (~1 ГБ).
./mvnw -B -ntp verify -DskipTests
