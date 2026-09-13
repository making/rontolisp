#!/bin/bash
# Compiles wasm.lisp / nofold.lisp at both optimize levels (--no-gc, WASI on), runs
# them under node:wasi, and diffs stdout against the interpreter.
set -e
# JAR: a build with prototype.diff applied (or an unpatched one, to see the BEFORE layout).
P="$(cd "$(dirname "$0")" && pwd)"
J=${JAR:?set JAR to a rontolisp-0.1.0-SNAPSHOT-exec.jar}
ARG='runtime-string-arg'
cd $P
java -jar $J interp.lisp > interp.txt
echo "--- interpreter output ($(wc -l < interp.txt | tr -d ' ') lines) ---"
cat interp.txt
for lv in off size; do
  java -jar $J wasm.lisp -o wasm-$lv.wasm --no-gc --optimize=$lv
  node runwasi.mjs wasm-$lv.wasm "$ARG" > wasm-$lv.txt
  if diff interp.txt wasm-$lv.txt > /dev/null; then echo "probe optimize=$lv: IDENTICAL to interpreter"; else echo "probe optimize=$lv: DIFFERS"; diff interp.txt wasm-$lv.txt || true; fi
  echo "  wasm-$lv.wasm raw=$(wc -c < wasm-$lv.wasm | tr -d ' ')"
  node ../dsect.mjs wasm-$lv.wasm | grep 'data payload'
  node dumpdata.mjs wasm-$lv.wasm
  wasm-tools validate wasm-$lv.wasm && echo "  wasm-tools validate: ok"
done
# nofold: interpreter side is the same body with printing h1
cat > nofold-interp.lisp <<'EOF'
(defun h1 (a) (princ "[h1 ") (princ a) (princ "]") (terpri))
(defun c1 () (h1 "only-folded"))
(defun c2 () (h1 "both-ways") (print (length "both-ways")))
(defun c8 (s) (h1 s) (h1 "after"))
(defun run-all (s) (c1) (c2) (c8 s) (princ "done") (terpri))
(run-all "runtime-string-arg")
EOF
java -jar $J nofold-interp.lisp > nofold-interp.txt
for lv in off size; do
  java -jar $J nofold.lisp -o nofold-$lv.wasm --no-gc --optimize=$lv
  node runwasi.mjs nofold-$lv.wasm "$ARG" > nofold-$lv.txt
  if diff nofold-interp.txt nofold-$lv.txt > /dev/null; then echo "nofold optimize=$lv: IDENTICAL to interpreter"; else echo "nofold optimize=$lv: DIFFERS"; diff nofold-interp.txt nofold-$lv.txt || true; fi
  node ../dsect.mjs nofold-$lv.wasm | grep -A3 'data payload'
done
