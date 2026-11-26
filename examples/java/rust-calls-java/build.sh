#!/usr/bin/env bash
#
# Rust calls Java: build both components, compose them with wac, run the result.
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

echo "== 1. the Java component (exports vowel-count; reactor) =="
"$MVN" -q clean package
cp target/rust-calls-java.wasm counter.wasm

echo "== 2. the Rust component (the one ../../wit/rust-calls-lisp shares) =="
RUST=../../wit/rust-calls-lisp/rust-describer
( cd "$RUST" && cargo component build --release --target wasm32-unknown-unknown )
cp "$RUST"/target/wasm32-unknown-unknown/release/describer.wasm describer.wasm

echo "== 3. compose: plug the Java counter into the Rust describer =="
wac plug describer.wasm --plug counter.wasm -o vowels.wasm

echo "== 4. run (the host invokes the Rust export, which calls back into Java) =="
wasmtime run --invoke 'describe("hello world")' vowels.wasm
