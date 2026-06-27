#!/usr/bin/env bash
# Train the hiragana recognizer offline and bake the learned weights into a
# WASM inference module for the browser demo.
#
#   examples/hiragana/gen.sh            # train (JVM) + build infer.wasm
#
# Pipeline:
#   1. train.lisp  = common.lisp + prototypes.lisp + train-main.lisp
#      run on the interpreter/JVM; its stdout is weights.lisp (a single
#      (defparameter *weights* ...) form, with ';;' progress comments).
#   2. infer.lisp  = common.lisp + weights.lisp + infer-main.lisp
#      compiled to infer.wasm (WASI Preview 1, WASM GC).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "[1/4] assembling train.lisp"
cat "$here/common.lisp" "$here/prototypes.lisp" "$here/train-main.lisp" \
  > "$here/train.lisp"

echo "[2/4] training (this runs SGD; progress prints as ';;' comments)"
# Train on the JVM (compile then run) -- much faster than the interpreter.
# The compiled class is named after the -o file, so it must be path-free:
# compile and run from inside this directory.
( cd "$here" \
    && java -jar "$jar" train.lisp -o Train.class \
    && java Train > weights.lisp \
    && rm -f Train.class )
grep '^;;' "$here/weights.lisp" || true   # echo the progress comments

echo "[3/4] assembling infer.lisp"
cat "$here/common.lisp" "$here/weights.lisp" "$here/infer-main.lisp" \
  > "$here/infer.lisp"

echo "[4/4] compiling infer.lisp -> infer.wasm"
java -jar "$jar" "$here/infer.lisp" -o "$here/infer.wasm"

rm -f "$here/Train.class"
echo "done. infer.wasm ready ($(wc -c < "$here/infer.wasm") bytes)."
echo "Serve this dir over http and open index.html, e.g.:"
echo "  python3 -m http.server 8000 --directory \"$here\""
