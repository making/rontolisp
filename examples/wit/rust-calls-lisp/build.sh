#!/usr/bin/env bash
#
# Rust calls Lisp: build both components, compose them with wac, run the result.
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

echo "== 1. the Lisp component (exports vowel-count; reactor) =="
"$RONTOLISP" counter.lisp -o counter.wasm --component --optimize

echo "== 2. the Rust component (cargo-component builds a component directly) =="
# The crate was scaffolded with:
#   cargo component new --lib rust-describer --name describer --namespace example
# targeting the shared ../wit (see rust-describer/Cargo.toml). No adapter needed --
# a wasm32-unknown-unknown reactor imports no WASI.
( cd rust-describer && cargo component build --release --target wasm32-unknown-unknown )
cp rust-describer/target/wasm32-unknown-unknown/release/describer.wasm describer.wasm

echo "== 3. compose: plug the Lisp counter into the Rust describer =="
wac plug describer.wasm --plug counter.wasm -o vowels.wasm

echo "== 4. run (the host invokes the Rust export, which calls back into Lisp) =="
wasmtime run -W gc=y --invoke 'describe("hello world")' vowels.wasm
