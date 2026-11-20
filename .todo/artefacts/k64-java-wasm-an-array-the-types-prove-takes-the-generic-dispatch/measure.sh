#!/bin/bash
# Compiles the programs of this directory and YAVI's examples to wasm with the size of
# every function, and prints the module sizes and the zone reader's functions.
# usage: measure.sh <exec jar> <out dir>   (from the repository's root)
set -e
jar=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
out=$(mkdir -p "$2" && cd "$2" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
root=$(pwd)
for p in Zone SieveLoop LongSum ZoneLoop; do
  (cd "$here" && java -Drontolisp.wasm.debug-func-sizes=true -jar "$jar" $p.java -o "$out/$p.wasm" 2> "$out/$p.sizes")
  echo "$p.wasm $(stat -c %s "$out/$p.wasm")"
done
for p in Booking Member; do
  (cd "$root/examples/java/yavi" && java -Drontolisp.wasm.debug-func-sizes=true -jar "$jar" \
    --java-source-dep am.ik.yavi:yavi:0.18.0 $p.java -o "$out/$p.wasm" 2> "$out/$p.sizes")
  echo "$p.wasm $(stat -c %s "$out/$p.wasm")"
done
echo "zone reader: $(grep -E 'ZoneChanges\.(region|find|rule|written|number)\(' "$out/Zone.sizes" | awk '{s+=$2} END {print s}')"
grep -E 'ZoneChanges\.(region|find|rule|written|number|placed|ruled|last|wall)\(' "$out/Zone.sizes" | awk '{print $2, $4}'
