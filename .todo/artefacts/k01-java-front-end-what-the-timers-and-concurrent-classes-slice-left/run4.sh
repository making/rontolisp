#!/usr/bin/env bash
# run4.sh File.java : runs a program under the JDK and on the four backends and prints,
# per backend, whether its output is the JDK's (the numbers of objects and of pools
# dropped) or the lines that differ. JAR is the exec jar; LEGS narrows the backends.
set -u
JAR=${JAR:-$PWD/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
src=$(readlink -f "$1"); name=$(basename "$src" .java)
work=$(mktemp -d); cd "$work"
cp "$src" .
timeout 120 java "$name.java" > jdk.out 2> jdk.err; echo "status=$?" >> jdk.out
for leg in ${LEGS:-interp jvm wasm comp}; do
  case $leg in
    interp) timeout 120 java -jar "$JAR" "$name.java" > $leg.out 2> $leg.err; echo "status=$?" >> $leg.out;;
    jvm) mkdir -p j && (cd j && timeout 120 java -jar "$JAR" "../$name.java" -o Prog.class > ../$leg.cout 2>&1 && (timeout 120 java Prog > ../$leg.out 2> ../$leg.err; echo "status=$?" >> ../$leg.out));;
    wasm) timeout 120 java -jar "$JAR" "$name.java" -o p.wasm > $leg.cout 2>&1 && (timeout 120 wasmtime run p.wasm > $leg.out 2> $leg.err; echo "status=$?" >> $leg.out);;
    comp) timeout 120 java -jar "$JAR" "$name.java" -o c.wasm --component > $leg.cout 2>&1 && (timeout 120 wasmtime run c.wasm > $leg.out 2> $leg.err; echo "status=$?" >> $leg.out);;
  esac
  for f in jdk.out $leg.out; do [ -f $f ] && sed -i -E 's/@[0-9a-f]+/@/g; s/pool-[0-9]+/pool-N/g' $f; done
  if [ -f $leg.out ] && cmp -s jdk.out $leg.out; then echo "== $leg: same"; else echo "== $leg: DIFFERS"; [ -f $leg.cout ] && head -5 $leg.cout; [ -f $leg.out ] && diff jdk.out $leg.out | head -20; fi
done
echo "work=$work"
