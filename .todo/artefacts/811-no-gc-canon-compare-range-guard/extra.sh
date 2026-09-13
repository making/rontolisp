#!/bin/bash
cd "$(dirname "$0")" || exit 1
echo "=== A. wasmtime --invoke on probe-exp (before|after, size) -- second engine"
for spec in id_s8:-129 id_s8:-128 id_s8:127 id_s8:128 id_s8:256 id_s8:-256 id_s16:-32769 id_s16:-32768 id_s16:32767 id_s16:32768 id_s16:65536 id_s32:-2147483649 id_s32:-2147483648 id_s32:2147483647 id_s32:2147483648 id_s32:4294967296 id_s32:-4294967296 id_u32:-1 id_u32:0 id_u32:4294967295 id_u32:4294967296 id_u32:-9223372036854775808 id_u8:-1 id_u8:0 id_u8:255 id_u8:256 id_u16:65535 id_u16:65536 id_u64:-1 id_u64:0 id_u64:9223372036854775807; do
  fn=${spec%%:*}; arg=${spec##*:}
  line="$fn($arg):"
  for v in before after; do
    r=$(wasmtime run --invoke $fn -- probe-exp-$v-size.wasm $arg 2>&1 | grep -v "^warning" | tr '\n' ' ')
    case "$r" in *unreachable*) r="TRAP";; esac
    line="$line $v=$(echo $r | sed 's/ *$//')"
  done
  echo "$line"
done
echo "=== B. interpreter vs wasmtime oracle (after jar; wasi build, run export)"
cat probe-oracle.lisp > probe-oracle-interp.lisp; echo "(run)" >> probe-oracle-interp.lisp
java -jar ./after.jar probe-oracle-interp.lisp > oracle-interp.txt 2>&1
java -jar ./after.jar probe-oracle.lisp -o probe-oracle-after.wasm --no-gc --optimize=size && wasmtime run --invoke run probe-oracle-after.wasm 2>/dev/null > oracle-wasmtime-after.txt
java -jar ./before.jar probe-oracle.lisp -o probe-oracle-before.wasm --no-gc --optimize=size && wasmtime run --invoke run probe-oracle-before.wasm 2>/dev/null > oracle-wasmtime-before.txt
java -jar ./after.jar probe-oracle.lisp -o probe-oracle-after-off.wasm --no-gc --optimize=off && wasmtime run --invoke run probe-oracle-after-off.wasm 2>/dev/null > oracle-wasmtime-after-off.txt
cat oracle-interp.txt
diff oracle-interp.txt oracle-wasmtime-after.txt && echo ORACLE-INTERP-VS-WASMTIME-IDENTICAL
diff oracle-wasmtime-before.txt oracle-wasmtime-after.txt && echo ORACLE-BEFORE-AFTER-IDENTICAL
diff oracle-wasmtime-after-off.txt oracle-wasmtime-after.txt && echo ORACLE-OFF-SIZE-IDENTICAL
echo "=== C. per-width wrapper sizes, probe-exp (size level)"
node fnsizes.mjs probe-exp-before-size.wasm probe-exp-after-size.wasm
echo "=== C2. per-width wrapper sizes, probe-imp (size level)"
node fnsizes.mjs probe-imp-before-size.wasm probe-imp-after-size.wasm
echo "=== D. GC backend unaffected?"
for p in browser probe-exp probe-imp; do
  java -jar ./before.jar $p.lisp -o gc-$p-before.wasm --no-wasi --optimize=size
  java -jar ./after.jar $p.lisp -o gc-$p-after.wasm --no-wasi --optimize=size
  cmp gc-$p-before.wasm gc-$p-after.wasm && echo "GC $p IDENTICAL ($(wc -c < gc-$p-after.wasm) bytes)"
done
java -jar ./before.jar probe-exp.lisp -o gc-probe-exp-before-off.wasm --no-wasi --optimize=off
java -jar ./after.jar probe-exp.lisp -o gc-probe-exp-after-off.wasm --no-wasi --optimize=off
cmp gc-probe-exp-before-off.wasm gc-probe-exp-after-off.wasm && echo "GC probe-exp off IDENTICAL"
echo "=== E. i64.extend32_s (0xC4) engine acceptance"
cat > ext32.wat <<'EOF'
(module
  (func (export "canon32") (param i64) (result i64)
    local.get 0 local.get 0 i64.extend32_s i64.ne
    if unreachable end
    local.get 0))
EOF
wasm-tools parse ext32.wat -o ext32.wasm && wasm-tools validate ext32.wasm && echo "wasm-tools OK: $(xxd -p ext32.wasm | tr -d '\n' | grep -o 'c4' | wc -l) x c4 present"
for a in 2147483647 2147483648 -2147483648 -2147483649; do
  r=$(wasmtime run --invoke canon32 -- ext32.wasm $a 2>&1 | grep -v "^warning" | tr '\n' ' '); case "$r" in *unreachable*) r=TRAP;; esac; echo "wasmtime canon32($a) -> $r"
done
node -e '
const b=require("fs").readFileSync("ext32.wasm");
WebAssembly.instantiate(b).then(({instance:{exports:e}})=>{for(const a of [2147483647n,2147483648n,-2147483648n,-2147483649n]){try{console.log("node canon32("+a+") -> "+e.canon32(a))}catch(x){console.log("node canon32("+a+") -> TRAP "+x.constructor.name)}}})'
echo "=== F. fold decision drift: (:string :s32) import at N call sites"
for n in 1 2 3 4; do
  {
    echo '(rontolisp:wasm-import (quote host-put) :from "env" :as "host_put" :params (quote (:string :s32)) :returns nil)'
    echo '(defun go (x)'
    for k in $(seq 1 $n); do echo "  (host-put \"k$k\" x)"; done
    echo ')'
    echo '(rontolisp:wasm-export (quote go) :as "go" :params (quote (:s64)) :returns nil)'
  } > fold-$n.lisp
  for v in before after; do
    java -jar ./after.jar fold-$n.lisp >/dev/null 2>&1
    java -jar ./$v.jar fold-$n.lisp -o fold-$n-$v.wasm --no-gc --no-wasi --optimize=size
    nfuncs=$(node fnsizes.mjs fold-$n-$v.wasm | wc -l | tr -d ' ')
    echo "N=$n $v raw=$(wc -c < fold-$n-$v.wasm) funcs=$nfuncs $(wasm-tools print fold-$n-$v.wasm | grep -c 'call \$host_put\|call 0' | tr -d ' ') direct-calls"
  done
done
