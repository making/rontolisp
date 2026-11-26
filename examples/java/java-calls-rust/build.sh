#!/usr/bin/env bash
#
# Java calls Rust: build both components, compose them with wac, run the result.
#
# The Maven build needs the SNAPSHOTs: from a checkout
#   ../../../mvnw install -DskipTests
#   ../../../mvnw -f rontolisp-java-api/pom.xml install -DskipTests
#   ../../../mvnw -f rontolisp-maven-plugin/pom.xml install -DskipTests
# On PATH:
# a Rust toolchain with wasm32-unknown-unknown, wac (cargo install wac-cli),
# cargo-component and wasmtime 46+.
#
set -euo pipefail
cd "$(dirname "$0")"

MVN="${MVN:-mvn}"

echo "== 1. the Java command (imports example:textkit/casing) =="
"$MVN" -q clean package
cp target/java-calls-rust.wasm app.wasm

echo "== 2. the Rust component (the one ../../wit/lisp-calls-rust shares) =="
RUST=../../wit/lisp-calls-rust/rust-shouter
( cd "$RUST" && cargo component build --release --target wasm32-unknown-unknown )
cp "$RUST"/target/wasm32-unknown-unknown/release/shouter.wasm shouter.wasm

echo "== 3. compose: plug the Rust component into the Java app =="
wac plug app.wasm --plug shouter.wasm -o textkit.wasm

echo "== 4. run =="
wasmtime run textkit.wasm
