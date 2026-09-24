#!/bin/bash
# usage: minrun.sh kind "wasmtime flags" N...
# Builds min.py's module (N x struct.new; drop in one function) and times `wasmtime compile`.
set -u
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
kind=$1
flags=$2
shift 2
for n in "$@"; do
  python3 "$here/min.py" "$kind" "$n" "$work/m.wat" || exit 1
  wasm-tools parse "$work/m.wat" -o "$work/m.wasm" || exit 1
  start=$(date +%s.%N)
  # shellcheck disable=SC2086 -- flags is a deliberate word list
  wasmtime compile -W gc=y $flags "$work/m.wasm" -o "$work/m.cwasm"
  end=$(date +%s.%N)
  echo "$kind n=$n [$flags] $(echo "$end - $start" | bc) s"
done
