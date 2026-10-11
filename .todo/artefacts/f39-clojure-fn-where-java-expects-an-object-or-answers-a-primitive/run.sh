#!/usr/bin/env bash
# Compiles and runs ScanMoves against target/classes (./mvnw compile first).
# Prints each moved overload choice and the totals.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
CP="$ROOT/target/classes:$HOME/.m2/repository/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -cp "$CP" -d "$OUT" "$HERE/ScanMoves.java"
java -cp "$OUT:$CP" ScanMoves
