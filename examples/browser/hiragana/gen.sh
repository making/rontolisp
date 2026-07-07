#!/usr/bin/env bash
# Train the hiragana recognizer offline and bake the learned weights into a
# WASM inference module for the browser demo.
#
#   examples/browser/hiragana/gen.sh                         # (A) train (JVM) + build infer.wasm
#   examples/browser/hiragana/gen.sh --weights-from FILE.lisp # (B) bake a prebuilt weights file
#
# Pipeline (the .lisp files compose via (load ...), not concatenation -- a
# top-level literal load is a compile-time include on the compilers, and the
# interpreter loads at runtime; relative load paths resolve next to each file):
#   1. train.lisp  loads common.lisp + prototypes.lisp; run on the
#      interpreter/JVM, its stdout is weights.lisp (a single
#      (defparameter *weights* ...) form, with ';;' progress comments).
#   2. infer.lisp   loads common.lisp + weights.lisp; compiled to
#      infer.wasm (WASI Preview 1, WASM GC).
#
# The inference half (step 2) only depends on weights.lisp -- it does not care
# HOW the weights were produced.  --weights-from swaps in an externally trained
# weights file (e.g. the real-data Kuzushiji-49 build from tools/k49/, which
# also defines a 49-class *labels*) and SKIPS the rontolisp training in step 1.
# The default (no flag) is unchanged: the self-contained synthetic trainer.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

weights_from=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --weights-from)
      weights_from="${2:?--weights-from needs a path}"; shift 2 ;;
    --weights-from=*)
      weights_from="${1#*=}"; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)
      echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

if [[ -n "$weights_from" ]]; then
  # (B) Use externally trained weights.  Skip rontolisp training entirely.
  if [[ ! -f "$weights_from" ]]; then
    echo "--weights-from file not found: $weights_from" >&2
    exit 1
  fi
  if ! grep -q 'defparameter \*weights\*' "$weights_from"; then
    echo "refusing: $weights_from does not define (defparameter *weights* ...)" >&2
    exit 1
  fi
  echo "[1/3] using prebuilt weights: $weights_from (skipping rontolisp training)"
  cp "$weights_from" "$here/weights.lisp"
else
  # (A) Self-contained synthetic trainer (default).
  echo "[1/3] training (this runs SGD; progress prints as ';;' comments)"
  # Train on the JVM (compile then run) -- much faster than the interpreter.
  # train.lisp (load ...)s common.lisp + prototypes.lisp; the compiler
  # inlines those top-level loads.  The compiled class is named after the -o
  # file, so it must be path-free: compile and run from inside this directory
  # (which also makes the relative loads resolve here).
  ( cd "$here" \
      && java -jar "$jar" train.lisp -o Train.class \
      && java Train > weights.lisp \
      && rm -f Train.class )
  grep '^;;' "$here/weights.lisp" || true   # echo the progress comments
fi

echo "[2/3] compiling infer.lisp -> infer.wasm"
# infer.lisp (load ...)s common.lisp + the trained weights.lisp; the loads
# resolve relative to infer.lisp, so this works from any directory.
java -jar "$jar" "$here/infer.lisp" -o "$here/infer.wasm"

rm -f "$here/Train.class"
echo "done. infer.wasm ready ($(wc -c < "$here/infer.wasm") bytes)."
echo "Serve this dir over http and open index.html, e.g.:"
echo "  python3 -m http.server 8000 --directory \"$here\""
