#!/bin/bash
# usage: ab.sh N a.cwasm b.cwasm ... : alternating runs, min user time of each
n=$1; shift; declare -A mu
for f in "$@"; do mu[$f]=999; done
for i in $(seq $n); do for f in "$@"; do
  u=$( { /usr/bin/time -f '%U' wasmtime run --allow-precompiled $f > /dev/null; } 2>&1 | tail -1)
  mu[$f]=$(echo "if ($u < ${mu[$f]}) $u else ${mu[$f]}" | bc)
done; done
for f in "$@"; do echo "$f ${mu[$f]}"; done
