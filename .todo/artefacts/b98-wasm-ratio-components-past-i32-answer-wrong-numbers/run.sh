#!/usr/bin/env bash
# Interpreter vs WASM-GC (Preview 1, default and --optimize=size) over the ratio sweeps,
# and Scheme/Clojure decimal reads on all four backends against Python's float.
# Usage: run.sh path/to/rontolisp-exec.jar [seed]
set -u
JAR=$1; SEED=${2:-1}
HERE=$(cd "$(dirname "$0")" && pwd)
W=$(mktemp -d); cd "$W"
wasm() { wasmtime run -W gc=y,exceptions=y,function-references=y "$@"; }
for g in ops edges ties; do
  python3 "$HERE/gen_$g.py" "$SEED" > $g.lisp
  java -jar "$JAR" $g.lisp > i.out 2>&1
  java -jar "$JAR" $g.lisp -o d.wasm > /dev/null 2>&1 && wasm d.wasm > d.out 2>&1
  java -jar "$JAR" $g.lisp -o s.wasm --optimize=size > /dev/null 2>&1 && wasm s.wasm > s.out 2>&1
  echo "$g: lines=$(wc -l < i.out) default-diffs=$(diff i.out d.out | grep -c '^<') size-diffs=$(diff i.out s.out | grep -c '^<')"
done
python3 "$HERE/gen_bigint.py" "$SEED" vals.txt > big.lisp
java -jar "$JAR" big.lisp > i.out 2>&1
java -jar "$JAR" big.lisp -o d.wasm > /dev/null 2>&1 && wasm d.wasm > d.out 2>&1
echo "bigint: lines=$(wc -l < i.out) diffs=$(diff i.out d.out | grep -c '^<') python-mismatches=$(python3 -c "
vals=[int(x) for x in open('vals.txt').read().split()]
outs=open('d.out').read().split()
def f(v):
    try: return float(v)
    except OverflowError: return float('inf') if v > 0 else float('-inf')
print(sum(1 for v,o in zip(vals,outs) if float(o.replace('Infinity','inf')) != f(v)))")"
python3 "$HERE/gen_decimals.py" "$SEED" vals.txt > strs.txt
printf '(doseq [s [%s]]\n  (prn (read-string s)))\n' "$(cat strs.txt)" > dec.clj
printf '(for-each (lambda (s) (display (string->number s)) (newline)) (list %s))\n' "$(cat strs.txt)" > dec.scm
for f in dec.clj dec.scm; do
  java -jar "$JAR" $f > i.out 2>&1
  rm -f *.class; java -jar "$JAR" $f -o Dec.class > /dev/null 2>&1 && java -cp . Dec > j.out 2>&1
  java -jar "$JAR" $f -o d.wasm > /dev/null 2>&1 && wasm d.wasm > d.out 2>&1
  java -jar "$JAR" $f -o c.wasm --component > /dev/null 2>&1 && wasm c.wasm > c.out 2>&1
  echo "$f: lines=$(wc -l < i.out) jvm=$(diff i.out j.out | grep -c '^<') wasm=$(diff i.out d.out | grep -c '^<') component=$(diff i.out c.out | grep -c '^<') parseDouble-mismatches=$(python3 -c "
vals=open('vals.txt').read().split()
outs=open('i.out').read().split()
def p(o):
    for a,b in (('##Inf','inf'),('##-Inf','-inf'),('Infinity','inf'),('+inf.0','inf'),('-inf.0','-inf')): o=o.replace(a,b)
    return float(o)
print(sum(1 for v,o in zip(vals,outs) if p(o) != float(v) or (str(p(o))[0]=='-') != (str(float(v))[0]=='-')))")"
done
rm -rf "$W"
