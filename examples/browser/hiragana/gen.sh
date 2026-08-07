#!/usr/bin/env bash
# Train the hiragana recognizer offline, then compile the inference half to WASM
# for the browser demo.
#
#   examples/browser/hiragana/gen.sh
#
# Pipeline (the .lisp files compose via (load ...), not concatenation -- a
# top-level literal load is a compile-time include on the compilers, and the
# interpreter loads at runtime; relative load paths resolve next to each file):
#
#   1. train.lisp  -> weights.bin   loads dataset.lisp (K49 + synthetic glyphs)
#                                   and net.lisp (the ch07 SimpleConvNet), trains
#                                   with Adam and writes the RLW1 weight file.
#                                   Run compiled to the JVM with --simd; the
#                                   interpreter would take hours.
#   2. recognize.lisp -> infer.wasm the same net, exported as a host-callable
#                                   recognize() for the page.  It READS
#                                   weights.bin at startup -- nothing is baked in,
#                                   so the model is not capped by the JVM's
#                                   baked-constant ceiling.
#
# The real-handwriting half of the training set is Kuzushiji-49, which
# tools/k49/prepare-k49.py downloads and converts once (see tools/k49/README.md).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

if [[ ! -f "$here/data/k49-train.bin" ]]; then
  echo "training data not found: $here/data/k49-train.bin" >&2
  echo "Prepare it once (downloads Kuzushiji-49, ~80 MB; needs numpy + Pillow):" >&2
  echo "  python3 $here/tools/k49/prepare-k49.py" >&2
  exit 1
fi

echo "[1/2] training (Adam over K49 + the synthetic glyphs; a few minutes)"
# The compiled class is named after the -o file, so it must be path-free: compile
# and run from inside this directory (which also makes the relative data paths
# resolve here).  --simd routes the convolution's matrix products through the
# Vector API, which the JVM run then needs the incubator module for.
( cd "$here" \
    && java -jar "$jar" train.lisp -o Train.class --simd \
    && java --add-modules jdk.incubator.vector -Xmx8g -cp ".:$jar" Train \
    && rm -f Train.class )

echo "[2/2] compiling recognize.lisp -> infer.wasm"
# --optimize tree-shakes the runtime and the WASI import surface down to what the
# inference half reaches; the convnet itself dominates the module, so the win is
# small here -- but the page then links against exactly the imports the shim has.
java -jar "$jar" "$here/recognize.lisp" -o "$here/infer.wasm" --optimize

echo "done."
echo "  weights.bin  $(wc -c < "$here/weights.bin") bytes"
echo "  infer.wasm   $(wc -c < "$here/infer.wasm") bytes"
echo "Serve this dir over http and open index.html, e.g.:"
echo "  python3 -m http.server 8000 --directory \"$here\""
