#!/usr/bin/env bash
#
# Lisp calls Lisp over a resource: build the components, compose them with wac,
# run the result -- once with the component that exports the resource answering
# both interfaces, once with a third component answering `report` over the
# counters the first one keeps.
#
# Prerequisites on PATH: a rontolisp binary (or set RONTOLISP), wac
# (cargo install wac-cli) and wasmtime 46+.
#
#   RONTOLISP=../../../target/rontolisp ./build.sh
#   RONTOLISP=../../../target/rontolisp COUNTERS=Counters.java ./build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

RONTOLISP="${RONTOLISP:-rontolisp}"
# The file implementing the world counters: counters.lisp, Counters.java or
# counters.clj.
COUNTERS="${COUNTERS:-counters.lisp}"

echo "== 1. the component that exports the resource (a reactor) =="
"$RONTOLISP" "$COUNTERS" --java-source-path ../../../rontolisp-java-api/src/main/java \
  -o counters.wasm --component

echo "== 2. the command that imports its interfaces =="
"$RONTOLISP" app.lisp -o app.wasm --component

echo "== 3. compose: plug the exporting component into the command =="
wac plug app.wasm --plug counters.wasm -o composed.wasm

echo "== 4. run =="
wasmtime run composed.wasm

echo "== 5. the component that takes the resource it imports (a reactor) =="
"$RONTOLISP" reporter.lisp -o reporter.wasm --component

echo "== 6. compose all three: report from the reporter, registry from counters =="
wac compose composition.wac \
  --dep example:app=app.wasm \
  --dep example:counters=counters.wasm \
  --dep example:reporter=reporter.wasm \
  -o reported.wasm

echo "== 7. run =="
wasmtime run reported.wasm
