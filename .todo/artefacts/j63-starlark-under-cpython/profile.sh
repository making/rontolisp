#!/bin/bash
# usage: JAR=path/of/rontolisp-exec.jar profile.sh program [top] [--callers] [--offsets NAME#N]
# Compiles a program to wasm, runs it under the sampler (sample.c, built into the
# working directory on first use) and prints self and inclusive samples per function. With
# --offsets NAME#N also writes N.annot: that function's machine code (binutils objdump
# of the .cwasm, an ELF) with the samples that hit each instruction.
here=$(cd "$(dirname "$0")" && pwd)
[ -x ./sample ] || gcc -O2 -o ./sample $here/sample.c || exit 1
f=$1; shift; b=$(basename $f | sed 's/\.[a-z]*$//')
java -Drontolisp.wasm.debug-func-sizes=true -jar ${JAR:?path of the rontolisp exec jar} $f -o $b.wasm 2> $b.funcs || { tail -3 $b.funcs; exit 1; }
wasmtime compile $b.wasm -o $b.cwasm
./sample $b.samples 500 wasmtime run --profile=perfmap --allow-precompiled $b.cwasm > /dev/null
python3 $here/report.py $b.samples $b.funcs "$@" | tee $b.report | sed '/^-- offsets/,$d'
for a in "$@"; do case $a in *'#'*)
  n=${a##*#}
  sed -n '/^-- offsets/,$p' $b.report > $n.off
  objdump -d --no-show-raw-insn --disassemble="wasm[0]::function[$n]" $b.cwasm | grep -P '^\s+[0-9a-f]+:\t' > $n.asm
  python3 $here/annot.py $n.asm $n.off > $n.annot; echo "$n.annot: $(tail -1 $n.annot)";;
esac; done
