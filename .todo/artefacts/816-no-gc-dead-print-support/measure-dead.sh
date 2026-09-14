#!/bin/sh
# .todo/816: count the bytes a printing --no-gc module emits and never reads.
# Compiles each probe, strips the emitted binary, validates and re-runs both.
#   JAR=/abs/path/to/rontolisp-0.1.0-SNAPSHOT-exec.jar ./measure-dead.sh [outdir]
set -e
: "${JAR:?set JAR to the executable jar}"
OUT=${1:-out}
DIR=$(dirname "$0")
mkdir -p "$OUT"
for p in princ-literal princ-number princ-boolean print-literal; do
  case $p in princ-number|princ-boolean) ARG=42 ;; *) ARG= ;; esac
  java -jar "$JAR" "$DIR/$p.lisp" -o "$OUT/$p.wasm" --no-gc --optimize=size
  printf '%-14s raw=%-5s ' "$p" "$(wc -c < "$OUT/$p.wasm" | tr -d ' ')"
  KEEP_BRACKET=1 node "$DIR/dead816.mjs" "$OUT/$p.wasm" "$OUT/$p-data.wasm" | tr '\n' ' '
  echo
  wasm-tools validate "$OUT/$p-data.wasm"
  printf '  before: '; wasmtime run --invoke main "$OUT/$p.wasm" $ARG 2>/dev/null
  printf '  after : '; wasmtime run --invoke main "$OUT/$p-data.wasm" $ARG 2>/dev/null
done
# The two literal-only modules allocate nothing, so the heap bracket goes too.
for p in princ-literal print-literal; do
  node "$DIR/dead816.mjs" "$OUT/$p.wasm" "$OUT/$p-all.wasm"
  wasm-tools validate "$OUT/$p-all.wasm"
  printf '  %s full strip: ' "$p"; wasmtime run --invoke main "$OUT/$p-all.wasm"
done
