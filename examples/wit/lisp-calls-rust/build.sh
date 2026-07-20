#!/usr/bin/env bash
#
# Lisp calls Rust: build both components, compose them with wac, run the result.
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

echo "== 1. the Lisp command (imports example:textkit/casing) =="
"$RONTOLISP" app.lisp -o app.wasm --component --optimize

echo "== 2. the Rust component (cargo-component builds a component directly) =="
# The crate was scaffolded with:
#   cargo component new --lib rust-shouter --name shouter --namespace example
# targeting the shared ../wit (see rust-shouter/Cargo.toml). No adapter needed --
# a wasm32-unknown-unknown reactor imports no WASI.
( cd rust-shouter && cargo component build --release --target wasm32-unknown-unknown )
cp rust-shouter/target/wasm32-unknown-unknown/release/shouter.wasm shouter.wasm

echo "== 3. compose: plug the Rust component into the Lisp app =="
wac plug app.wasm --plug shouter.wasm -o textkit.wasm

echo "== 4. run =="
wasmtime run -W gc=y textkit.wasm
