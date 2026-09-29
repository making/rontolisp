#!/bin/bash
# cmpcli.sh <jarA> <jarB>: CLI compiles with both jars, every class file compared -- the ci-spec
# corpus (default, off, --dynamic), the mito probe, the jose suite and the examples that reach
# the runtime builders (java: interop, jvm-export, --simd, --gpu, served, clack, asdf systems).
# Run from the repository; the mito probe needs ~/.rontolisp/quicklisp.
T=$(cd "$(dirname "$0")" && pwd)
ROOT=${ROOT:-$(git rev-parse --show-toplevel)}
WORK=${WORK:-/tmp/a85-work}
R=$ROOT/src/test/resources
E=$ROOT/examples
ja=$(realpath "$1"); jb=$(realpath "$2")
mkdir -p "$WORK"
cd "$WORK" || exit 1
python3 - "$R/ci-spec.yaml" > corpus.lisp <<'PY'
import sys, yaml
for c in yaml.safe_load(open(sys.argv[1]))['cases']:
    s = c['source']
    sys.stdout.write(s if s.endswith('\n') else s + '\n')
PY
rm -rf cli
mkdir -p cli
one() {
  # one <name> <dir-to-run-in> <source> [args...]
  local name="$1" dir="$2" src="$3"; shift 3
  for side in a b; do
    local jar=$ja; [ $side = b ] && jar=$jb
    local out="$WORK/cli/$name-$side"
    mkdir -p "$out"
    (cd "$dir" && java -Xss512m -jar "$jar" "$src" -o "$out/P.class" "$@" > "$out/log.txt" 2>&1; echo $? > "$out/exit.txt")
  done
  local ea eb diffs=0 count=0
  ea=$(cat "$WORK/cli/$name-a/exit.txt"); eb=$(cat "$WORK/cli/$name-b/exit.txt")
  while IFS= read -r f; do
    count=$((count + 1))
    if ! cmp -s "$WORK/cli/$name-a/$f" "$WORK/cli/$name-b/$f"; then
      diffs=$((diffs + 1))
      echo "$name: DIFFERS $f (java $T/MethodDiff.java names the methods)"
    fi
  done < <(cd "$WORK/cli/$name-a" && find . -name '*.class' | sort)
  echo "$name: exit a=$ea b=$eb, $count class files, $diffs differ"
}
SP="$R/jose:$R/cl-json:$R/ironclad:$R/cl-base64:$R/split-sequence:$R/assoc-utils:$R/alexandria:$R/trivial-utf-8:$R/rove:$R/dissect:$R/cl-ppcre"
one corpus "$WORK" "$WORK/corpus.lisp" &
one corpus-off "$WORK" "$WORK/corpus.lisp" --optimize=off &
one corpus-dynamic "$WORK" "$WORK/corpus.lisp" --dynamic &
one mito "$WORK" "$T/mito-probe.lisp" &
one jose "$WORK" "$T/jose-suite.lisp" --system-path "$SP" &
wait
for f in console/contact-book.lisp console/word-frequency.lisp console/l-system.lisp ml/maze-rl.lisp \
  deep-learning-from-scratch/ch05/two-layer-net.lisp console/error-handling.lisp jvm/java-interop.lisp \
  jvm/swing.lisp net/http-handler.lisp net/dog-fetcher.lisp net/http-hello.lisp net/kv-server.lisp \
  net/echo-server.lisp net/hello-clack.lisp net/httpbin-ningle.lisp net/linalg-api.lisp \
  console/mandelbrot.lisp console/sorting.lisp; do
  name=$(echo "$f" | tr '/' '_' | sed 's/.lisp$//')
  one "$name" "$(dirname "$E/$f")" "$(basename "$f")" &
done
one alexandria "$E/asdf" alexandria-demo.lisp --system-path "$R/alexandria" &
one jzon "$E/asdf" jzon-demo.lisp --system-path "$R/jzon/src" &
one mustache "$E/asdf" mustache-demo.lisp --system-path "$R/cl-mustache" &
one kernels-library "$E/jvm" kernels-library.lisp --no-main &
one kernels-simd "$E/jvm" kernels-library.lisp --no-main --simd &
one gpu-kernels "$E/jvm/bench" gpu-kernels.lisp --gpu &
one twolayer-simd "$E/deep-learning-from-scratch/ch05" two-layer-net.lisp --simd &
wait
