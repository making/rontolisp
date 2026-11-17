#!/bin/bash
# usage: abw.sh N prog.star jarA jarB ..: wasm, alternating runs, min user seconds of each build
n=$1; f=$2; shift 2; declare -A mu; i=0
for j in "$@"; do b=$(basename $j .jar); java -jar $j $f -o ab-$b.wasm 2>/dev/null && wasmtime compile ab-$b.wasm -o ab-$b.cwasm; mu[$b]=999; done
for k in $(seq $n); do for j in "$@"; do b=$(basename $j .jar)
  u=$( { /usr/bin/time -f '%U' wasmtime run --allow-precompiled ab-$b.cwasm > /dev/null; } 2>&1 | tail -1)
  mu[$b]=$(echo "if ($u < ${mu[$b]}) $u else ${mu[$b]}" | bc)
done; done
for j in "$@"; do b=$(basename $j .jar); echo "$(basename $f) $b ${mu[$b]} $(stat -c %s ab-$b.wasm)"; done
