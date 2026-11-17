#!/bin/bash
# bench.sh <jar> <file.lisp|file.java> [runs] : best-of JVM / precompiled wasm seconds and sizes
. "$(dirname "$0")/env.sh"
jar=$1; f=$(readlink -f $2); runs=${3:-3}; n=$(basename $f); n=${n%.*}; d=$W/out/b-$n-$$; rm -rf $d; mkdir -p $d; cd $d
t() { local best=999; for i in $(seq $runs); do s=$(date +%s.%N); "$@" > last.txt 2>&1; e=$(date +%s.%N); best=$(echo "$best $s $e" | awk '{d=$3-$2; print (d<$1)?d:$1}'); done; printf "%.2f" $best; }
java -jar $jar $f -o Prog.class --class-name Prog 2>&1 | head -3
java -jar $jar $f -o p.wasm 2>&1 | head -3; wasmtime compile p.wasm -o p.cwasm
j=$(t java -cp . Prog); jo=$(head -c 40 last.txt | tr '\n' ' ')
w=$(t wasmtime run --allow-precompiled p.cwasm); wo=$(head -c 40 last.txt | tr '\n' ' ')
printf '%-28s jvm %s  wasm %s  | wasm %s B class %s B | %s| %s\n' $n $j $w $(stat -c %s p.wasm) $(stat -c %s Prog.class) "$jo" "$wo"
rm -rf $d
