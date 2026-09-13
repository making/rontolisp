#!/bin/bash
# usage: JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar bytes.sh
set -e
cd "$(dirname "$0")"
J=${JAR:?set JAR to a rontolisp-0.1.0-SNAPSHOT-exec.jar}
java -jar "$J" bytes-interp.lisp
for mode in "--no-gc" ""; do
  java -jar "$J" bytes.lisp -o bytes$mode.wasm $mode --no-wasi --optimize=size
  echo "--- $( [ -n "$mode" ] && echo "$mode" || echo "wasm GC" ) ---"
  node bytes.mjs bytes$mode.wasm
done
