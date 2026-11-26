#!/usr/bin/env bash
#
# Lisp calls Lisp over compound types: build both components, compose them with
# wac, run the result.
#
# Prerequisites on PATH: a rontolisp binary (or set RONTOLISP), wac
# (cargo install wac-cli) and wasmtime 46+.
#
#   RONTOLISP=../../../target/rontolisp ./build.sh
#   RONTOLISP=../../../target/rontolisp SHAPES=Shapes.java ./build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

RONTOLISP="${RONTOLISP:-rontolisp}"
# The file implementing the world: shapes.lisp, or Shapes.java.
SHAPES="${SHAPES:-shapes.lisp}"

echo "== 1. the component that exports the world (a reactor) =="
"$RONTOLISP" "$SHAPES" --java-source-path ../../../rontolisp-java-api/src/main/java \
  -o shapes.wasm --component

echo "== 2. the command that imports its interface =="
"$RONTOLISP" app.lisp -o app.wasm --component

echo "== 3. compose: plug the exporting component into the command =="
wac plug app.wasm --plug shapes.wasm -o composed.wasm

echo "== 4. run =="
wasmtime run composed.wasm
