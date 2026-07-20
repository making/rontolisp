#!/usr/bin/env bash
#
# A three-component, two-language pipeline composed with ONE `wac compose`.
#
# Prerequisites on PATH: a rontolisp binary (or set RONTOLISP), a Rust toolchain
# with the wasm32-unknown-unknown target, wasm-tools, wac (cargo install wac-cli)
# and wasmtime 46+.
#
#   RONTOLISP=../../../target/rontolisp ./build.sh
#
set -euo pipefail
cd "$(dirname "$0")"

RONTOLISP="${RONTOLISP:-rontolisp}"

echo "== 1. the two Lisp components =="
"$RONTOLISP" app.lisp   -o app.wasm   --component --optimize
"$RONTOLISP" stats.lisp -o stats.wasm --component --optimize

echo "== 2. the Rust component (cargo-component builds a component directly) =="
# The crate was scaffolded with:
#   cargo component new --lib rust-shouter --name shouter --namespace example
# targeting the shared ../wit (see rust-shouter/Cargo.toml). No adapter needed --
# a wasm32-unknown-unknown reactor imports no WASI.
( cd rust-shouter && cargo component build --release --target wasm32-unknown-unknown )
cp rust-shouter/target/wasm32-unknown-unknown/release/shouter.wasm shouter.wasm

echo "== 3. compose all three with one wac compose =="
# stats -> shouter -> app, wired by composition.wac. `wac plug` cannot do this in
# one step (it fills a single socket; it will not wire a plug into another plug).
wac compose composition.wac \
  --dep example:app=app.wasm \
  --dep example:shouter=shouter.wasm \
  --dep example:stats=stats.wasm \
  -o pipeline.wasm

echo "== 4. run =="
wasmtime run -W gc=y pipeline.wasm
