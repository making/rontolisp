#!/bin/sh
# Compiles one program to wasm with the dump of every function's size, runs it and prints
# the file's size and its sections.
# usage: measure.sh <exec jar> <out dir> <label> <source tree> <pkg/File.java> <source option...>
set -e
jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
out=$2; label=$3; tree=$4; file=$5; shift 5
mkdir -p "$out"
out=$(cd "$out" && pwd)
cd "$tree"
java -Drontolisp.wasm.debug-func-sizes=true -jar "$jar" "$@" "$file" -o "$out/$label.wasm" > "$out/$label.sizes" 2>&1
wasmtime run "$out/$label.wasm" > "$out/$label.out" 2>&1 || true
echo "== $label: $(stat -c %s "$out/$label.wasm") B, $(wc -l < "$out/$label.out") lines"
wasm-objdump -h "$out/$label.wasm" 2>/dev/null | grep -E '^\s+(Code|Data|Global|Function|Type)' | awk '{print $1, $4}'
