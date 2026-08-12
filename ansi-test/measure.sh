#!/usr/bin/env bash
# Run the ANSI test suite against the interpreter and rewrite results/interpreter.md.
#
#   ansi-test/measure.sh              # every chapter
#   ansi-test/measure.sh cons numbers # named chapters only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -d ansi-test/suite ] || ansi-test/fetch.sh

./mvnw -q -o test-compile -DskipTests

exec java -cp "target/test-classes:target/classes" \
  -Drontolisp.ansi.root=ansi-test \
  -Drontolisp.ansi.stall="${ANSI_STALL:-45}" \
  am.ik.rontolisp.ansi.AnsiCompliance "$@"
