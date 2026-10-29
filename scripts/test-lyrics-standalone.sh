#!/usr/bin/env bash
set -euo pipefail
# Uses an existing JDK compiler module; no Android SDK, Gradle, network or test dependencies.
# -source/-target also work on trimmed runtimes without ct.sym; this is not an API-level build.
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cd "$root"
java_home="$("$java_cmd" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')"
if [[ -f "$java_home/lib/ct.sym" ]]; then
  compatibility=(--release 11)
else
  echo "Note: this runtime lacks ct.sym; checking Java 11 syntax/bytecode with current runtime APIs, not Android/API compatibility." >&2
  compatibility=(-source 11 -target 11 -Xlint:-options)
fi
"$java_cmd" -m jdk.compiler/com.sun.tools.javac.Main "${compatibility[@]}" -d "$out" \
  common/src/main/java/com/heavendoor/ultralab/UltraLyricsState.java \
  common/src/test/java/com/heavendoor/ultralab/UltraLyricsStateStandaloneTest.java
"$java_cmd" -cp "$out" com.heavendoor.ultralab.UltraLyricsStateStandaloneTest
