#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SOURCES=(
  app/src/main/java/com/worta/tanks/Json.java
  app/src/main/java/com/worta/tanks/RoomEngine.java
  app/src/main/java/com/worta/tanks/LocalHttpServer.java
  app/src/main/java/com/worta/tanks/Protocol.java
  app/src/main/java/com/worta/tanks/TerrainState.java
  app/src/main/java/com/worta/tanks/EndpointAddress.java
  tests/RoomServerTest.java
)
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -source 17 -target 17 -d "$OUT" "${SOURCES[@]}"
else
  java com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -d "$OUT" "${SOURCES[@]}"
fi
java -ea -cp "$OUT" com.worta.tanks.RoomServerTest
