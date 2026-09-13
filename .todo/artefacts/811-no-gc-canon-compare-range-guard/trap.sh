#!/bin/bash
# Trap-set comparison: BEFORE/AFTER jars x off/size x exp/imp probes, under Node
# and wasmtime; plus the interpreter-vs-wasmtime oracle.
cd "$(dirname "$0")" || exit 1
for v in before after; do
  for lvl in off size; do
    for p in exp imp; do
      java -jar ./$v.jar probe-$p.lisp -o probe-$p-$v-$lvl.wasm --no-gc --no-wasi --optimize=$lvl || echo "COMPILE FAIL $p $v $lvl"
      wasm-tools validate probe-$p-$v-$lvl.wasm || echo "INVALID $p $v $lvl"
      node trap.mjs probe-$p-$v-$lvl.wasm > trap-$p-$v-$lvl.txt 2>&1
      echo "trap-$p-$v-$lvl.txt: $(wc -l < trap-$p-$v-$lvl.txt) calls, $(grep -c TRAP trap-$p-$v-$lvl.txt) traps, raw=$(wc -c < probe-$p-$v-$lvl.wasm)"
    done
  done
done
echo "--- before vs after (must be empty)"
for lvl in off size; do for p in exp imp; do
  diff trap-$p-before-$lvl.txt trap-$p-after-$lvl.txt > /dev/null && echo "IDENTICAL $p $lvl" || { echo "DIFFER $p $lvl"; diff trap-$p-before-$lvl.txt trap-$p-after-$lvl.txt; }
done; done
echo "--- off vs size (must be empty)"
for v in before after; do for p in exp imp; do
  diff trap-$p-$v-off.txt trap-$p-$v-size.txt > /dev/null && echo "IDENTICAL $v $p" || { echo "DIFFER $v $p"; diff trap-$p-$v-off.txt trap-$p-$v-size.txt; }
done; done
echo "--- wasmtime --invoke on probe-exp (after, size) -- second engine"
for spec in id_s8:-129 id_s8:-128 id_s8:127 id_s8:128 id_s8:256 id_s16:-32769 id_s16:-32768 id_s16:32767 id_s16:32768 id_s32:-2147483649 id_s32:-2147483648 id_s32:2147483647 id_s32:2147483648 id_s32:4294967296 id_u32:-1 id_u32:0 id_u32:4294967295 id_u32:4294967296 id_u8:255 id_u8:256 id_u16:65535 id_u16:65536; do
  fn=${spec%%:*}; arg=${spec##*:}
  for v in before after; do
    r=$(wasmtime run --invoke $fn probe-exp-$v-size.wasm -- $arg 2>&1 | grep -v "^warning" | tr '\n' ' ' | sed 's/  */ /g')
    case "$r" in *unreachable*) r="TRAP unreachable";; esac
    echo "$v $fn($arg) -> $r"
  done
done | sort | awk '{k=$2; s=$0; sub(/^(before|after) /,"",s); a[k]=a[k]"|"s} END{for(k in a) print k": "a[k]}' | sort
echo "--- interpreter vs wasmtime oracle"
java -jar ./after.jar probe-oracle.lisp > oracle-interp.txt 2>&1
java -jar ./after.jar probe-oracle.lisp -o probe-oracle-after.wasm --no-gc --optimize=size && wasmtime run probe-oracle-after.wasm > oracle-wasmtime.txt 2>&1
java -jar ./before.jar probe-oracle.lisp -o probe-oracle-before.wasm --no-gc --optimize=size && wasmtime run probe-oracle-before.wasm > oracle-wasmtime-before.txt 2>&1
cat oracle-interp.txt
diff oracle-interp.txt oracle-wasmtime.txt && echo ORACLE-IDENTICAL
diff oracle-wasmtime-before.txt oracle-wasmtime.txt && echo ORACLE-BEFORE-AFTER-IDENTICAL
