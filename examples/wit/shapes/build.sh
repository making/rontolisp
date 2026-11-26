#!/usr/bin/env bash
#
# Lisp calls Lisp over compound types: build both components, compose them with
# wac, run the result.
#
# Prerequisites on PATH: a rontolisp binary (or set RONTOLISP), wac
# (cargo install wac-cli) and wasmtime 46+.
#
#   RONTOLISP=../../../target/rontolisp ./build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

RONTOLISP="${RONTOLISP:-rontolisp}"

echo "== 1. the component that exports the world (a reactor) =="
"$RONTOLISP" shapes.lisp -o shapes.wasm --component

echo "== 2. the command that imports its interface =="
"$RONTOLISP" app.lisp -o app.wasm --component

echo "== 3. compose: plug the exporting component into the command =="
wac plug app.wasm --plug shapes.wasm -o composed.wasm

echo "== 4. run =="
wasmtime run composed.wasm
