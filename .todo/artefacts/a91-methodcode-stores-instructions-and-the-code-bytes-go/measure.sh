#!/bin/bash
# measure.sh JAR LABEL [RUNS]: cold CLI compiles (-o P.class, default --optimize) of the ci-spec
# corpus and the mito probe (a85's mito-probe.lisp; needs ~/.rontolisp/quicklisp), each RUNS
# times: wall time, the [write] line (-Drontolisp.jvm.debug-write=true) and the class sizes.
# ROOT is the repository (default: the current directory); outputs go to $WORK/measure/LABEL.
ROOT=${ROOT:-$(pwd)}
WORK=${WORK:-/tmp/a91-work}
A85=$ROOT/.todo/artefacts/a85-jvm-runtime-builders-on-jvmasm-move-onto-methodcode
jar=$(realpath "$1"); label=$2; runs=${3:-3}
M=$WORK/measure/$label
mkdir -p "$M"
if [ ! -s "$WORK/corpus.lisp" ]; then
  python3 - "$ROOT/src/test/resources/ci-spec.yaml" > "$WORK/corpus.lisp" <<'PY'
import sys, yaml
for c in yaml.safe_load(open(sys.argv[1]))['cases']:
    s = c['source']
    sys.stdout.write(s if s.endswith('\n') else s + '\n')
PY
fi
for prog in corpus mito; do
  src=$WORK/corpus.lisp
  [ $prog = mito ] && src=$A85/mito-probe.lisp
  for r in $(seq 1 "$runs"); do
    out=$M/$prog-$r
    rm -rf "$out"; mkdir -p "$out"
    start=$(date +%s%N)
    (cd "$WORK" && java -Xss512m -Drontolisp.jvm.debug-write=true -jar "$jar" "$src" -o "$out/P.class" > "$out/log.txt" 2>&1)
    code=$?
    end=$(date +%s%N)
    sizes=$(cd "$out" && for f in $(find . -name 'P*.class' | sort); do printf '%s=%s ' "$f" "$(stat -c %s "$f")"; done)
    echo "$label $prog run$r exit=$code wall=$(( (end - start) / 1000000 ))ms $(grep -h '^\[write\]' "$out/log.txt" | head -1) :: $sizes"
  done
done
