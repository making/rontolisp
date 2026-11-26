#!/bin/bash
# usage: perfunc.sh file.wasm [wasmtime flags...]
# Serial `wasmtime compile` with Cranelift's pass timings logged; prints the 8 slowest
# functions as ms, by ORDINAL -- the order Cranelift compiled them in, which is the defined
# function order: function index = ordinal - 1 + the number of imported functions.
set -u
f=$1
shift
out=$(mktemp)
trap 'rm -f "$out"' EXIT
WASMTIME_LOG=cranelift_codegen::timing=debug wasmtime compile -C parallel-compilation=n \
  -W gc=y,function-references=y,exceptions=y,tail-call=y "$@" "$f" -o "$out" 2>&1 |
  awk '/Starting Translate WASM function/ {n++}
       /Ending/ {split($0, a, ": "); v = a[length(a)]; gsub("ms", "", v)}
       /Ending Translate WASM function/ {tr[n] += v}
       /Ending Compilation passes/ {cp[n] += v}
       /Ending Register allocation/ {ra[n] += v}
       END {for (i in cp) print tr[i] + cp[i], "ordinal=" i, "translate=" tr[i], "passes=" cp[i], "regalloc=" ra[i]}' |
  sort -n -r | head -8
