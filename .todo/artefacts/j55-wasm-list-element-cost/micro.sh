#!/bin/bash
# usage: micro.sh "<compiler command>" tag file.lisp|file.star ...
# Compiles each program to WASM preview 1, precompiles it, and prints the best of five
# user+sys seconds of `wasmtime run`, the module's size and the head of its output.
R=$1; tag=$2; shift 2
for f in "$@"; do
  b=$(basename $f .lisp); b=${b%.star}
  $R $f -o $b-$tag.wasm >/dev/null 2>$b-$tag.err || { echo "$b: COMPILE FAILED"; tail -3 $b-$tag.err; continue; }
  wasmtime compile $b-$tag.wasm -o $b-$tag.cwasm 2>/dev/null
  m=999
  for i in 1 2 3 4 5; do
    t=$( { /usr/bin/time -f "%U %S" wasmtime run --allow-precompiled $b-$tag.cwasm > $b-$tag.out; } 2>&1 | tail -1 | awk '{print $1+$2}')
    m=$(echo "if ($t < $m) $t else $m" | bc)
  done
  echo "$b [$tag]: $m s  size $(stat -c %s $b-$tag.wasm)  out $(head -c 60 $b-$tag.out | tr '\n' ' ')"
done
