#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cd "$root"
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_cmd" -m jdk.compiler/com.sun.tools.javac.Main -source 11 -target 11 -Xlint:-options -d "$out" \
 common/src/main/java/com/heavendoor/ultralab/UltraDataMode.java \
 common/src/test/java/com/heavendoor/ultralab/UltraDataModeStandaloneTest.java
"$java_cmd" -cp "$out" com.heavendoor.ultralab.UltraDataModeStandaloneTest
python3 scripts/test-read-only-boundaries.py

python3 scripts/test-read-only-session.py
