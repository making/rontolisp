#!/bin/sh
# Compiles each program with a jar to .wasm, a component and a class; writes the sizes and checksums.
# usage: measure.sh <jar> <outdir>
jar=$1; out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p $out
for f in $here/progs/*.java $here/progs/*.lisp $here/shop/Main.java; do
  n=$(basename $f); n=${n%.*}; [ "$n" = Main ] && n=shop
  d=$(dirname $f)
  (cd $d && java -jar $jar $(basename $f) -o $out/$n.wasm >/dev/null 2>$out/$n.wasm.err)
  (cd $d && java -jar $jar $(basename $f) -o $out/$n-c.wasm --component >/dev/null 2>$out/$n-c.wasm.err)
  mkdir -p $out/$n-cls
  (cd $d && java -jar $jar $(basename $f) -o $out/$n-cls/$n.class >/dev/null 2>$out/$n.cls.err)
done
cd $out && for x in *.wasm *-cls/*.class; do printf '%s %s %s\n' "$x" "$(wc -c < $x)" "$(sha1sum < $x | cut -c1-12)"; done > sizes.txt
