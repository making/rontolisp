#!/usr/bin/env bash
# Compile Calc.java to calc.wasm and call its exports with wasmtime.
#
# --no-wasi: a program with no main is a reactor -- the public static methods
#   of Calc are its exports, and the module imports nothing. It is wasm-GC,
#   which wasmtime 47+ runs without a flag.
# Needs the JAR (from the repository root: ./mvnw clean package) and wasmtime.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

java -jar "$jar" "$here/Calc.java" -o "$here/calc.wasm" --no-wasi

# --invoke NAME, then the arguments after the module. wasmtime warns that
# --invoke with arguments and results is experimental.
wasmtime run --invoke add "$here/calc.wasm" 1 2     # 3
wasmtime run --invoke fib "$here/calc.wasm" 10      # 55
