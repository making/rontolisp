#!/bin/bash
# usage: abw.sh N prog.star a.cwasm b.cwasm ... : alternating runs incl. python3, min wall and min user+sys of each
n=$1; shift; star=$1; shift
declare -A mw mu
run() { local key=$1; shift; local o; o=$( { /usr/bin/time -f '%e %U %S' "$@" > /dev/null; } 2>&1 | tail -1); set -- $o; local w=$1 us=$(echo "$2 + $3" | bc)
  [ -z "${mw[$key]}" ] && mw[$key]=999 && mu[$key]=999
  mw[$key]=$(echo "if ($w < ${mw[$key]}) $w else ${mw[$key]}" | bc); mu[$key]=$(echo "if ($us < ${mu[$key]}) $us else ${mu[$key]}" | bc); }
for i in $(seq $n); do run python python3 $star; for f in "$@"; do run $f wasmtime run --allow-precompiled $f; done; done
for k in python "$@"; do echo "$k wall ${mw[$k]} cpu ${mu[$k]}"; done
