#!/bin/bash
# usage: watrun.sh N shape[:run]...
# Times `wasmtime compile` of each hand-written shape of N list cells (wat.py).
set -u
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
n=$1
shift
for s in "$@"; do
  shape=${s%%:*}
  run=${s#*:}
  [ "$run" = "$s" ] && run=16
  python3 "$here/wat.py" "$shape" "$n" "$work/w.wat" "$run"
  wasm-tools parse "$work/w.wat" -o "$work/w.wasm" || exit 1
  start=$(date +%s.%N)
  wasmtime compile -W gc=y,function-references=y "$work/w.wasm" -o "$work/w.cwasm"
  end=$(date +%s.%N)
  echo "$s n=$n $(echo "$end - $start" | bc) s"
done
