#!/bin/bash
# sz.sh A.jar B.jar File.java...: wasm bytes of each program under two exec jars, in parallel
A=$1; B=$2; shift 2
o=$(mktemp -d)
for f in "$@"; do
  ( b=$(basename "$f" .java)
    java -jar "$A" "$f" -o "$o/$b-a.wasm" >/dev/null 2>&1
    java -jar "$B" "$f" -o "$o/$b-b.wasm" > "$o/$b.err" 2>&1
    echo "$b: $(stat -c %s "$o/$b-a.wasm" 2>/dev/null) -> $(stat -c %s "$o/$b-b.wasm" 2>/dev/null) $(head -c 200 "$o/$b.err")" ) &
done
wait
