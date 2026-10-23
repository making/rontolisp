#!/usr/bin/env bash
# Verifies lispresen: renders examples/intro.lisp with --embed (its image sits beside it)
# on the interpreter, the JVM, WASM, the WASM component, a native executable and SBCL,
# requires each output to equal examples/intro.html and the page image it draws to equal
# examples/intro.png byte for byte and the deck to raise no diagnostic, then runs the unit
# tests on the interpreter. SBCL is the reference Common Lisp: lispresen is written
# in portable Common Lisp, with rontolisp-only code behind #+rontolisp.
#
#   lispresen/check.sh            # verify
#   lispresen/check.sh --update   # rewrite the golden HTML and PNG from the interpreter first
#   lispresen/check.sh --exe PATH # verify only the lispresen executable at PATH (no jar needed)
#
# Needs the executable jar (./mvnw package; RONTOLISP_JAR overrides its path), java and
# wasmtime; the SBCL leg is skipped when sbcl is not on PATH. The unit tests stay on
# rontolisp: rove on SBCL needs bordeaux-threads, which is not vendored here.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
jar=${RONTOLISP_JAR:-$root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
deck=$here/examples/intro.lisp
golden=$here/examples/intro.html
golden_png=$here/examples/intro.png
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [[ ${1:-} == --update ]]; then
  (cd "$here/examples" && java -jar "$jar" "$here/cli.lisp" -- - -o - --embed < "$deck" > "$golden")
fi

status=0
check() {
  local name=$1
  shift
  # The deck comes from standard input, so its files resolve against the working directory
  # and the page image is written there: each leg runs in its own copy of examples/.
  local dir=$work/$name
  cp -R "$here/examples" "$dir"
  rm -f "$dir/intro.png"
  if (cd "$dir" && "$@" - -o - --embed < "$deck") > "$work/$name.html" 2> "$work/$name.err" \
      && [[ ! -s $work/$name.err ]] && cmp -s "$golden" "$work/$name.html" \
      && cmp -s "$golden_png" "$dir/intro.png"; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    cat "$work/$name.err"
    cmp "$golden" "$work/$name.html" || true
    cmp "$golden_png" "$dir/intro.png" || true
    status=1
  fi
}

if [[ ${1:-} == --exe ]]; then
  check "$(basename "$2")" "$2"
  exit $status
fi

check interpreter java -jar "$jar" "$here/cli.lisp" --
java -jar "$jar" "$here/cli.lisp" -o "$work/lispresen.jar" > /dev/null
check jvm java -jar "$work/lispresen.jar"
java -jar "$jar" "$here/cli.lisp" -o "$work/lispresen.wasm" > /dev/null
check wasm wasmtime run --dir . "$work/lispresen.wasm"
java -jar "$jar" "$here/cli.lisp" --component -o "$work/lispresen-component.wasm" > /dev/null
check wasm-component wasmtime run --dir . "$work/lispresen-component.wasm"
# A jar built without the precompiler for this platform cannot make an executable.
if java -jar "$jar" "$here/cli.lisp" --native -o "$work/lispresen" > "$work/native.log" 2>&1; then
  check native "$work/lispresen"
else
  echo "skip native: $(head -1 "$work/native.log")"
fi
if command -v sbcl > /dev/null; then
  check sbcl sbcl --script "$here/cli.lisp"
else
  echo "skip sbcl: not on PATH"
fi

resources=$root/src/test/resources
if (cd "$here/test" && java -jar "$jar" lispresen-test.lisp \
    --system-path "$resources/rove:$resources/dissect:$resources/cl-ppcre") > "$work/test.log" 2>&1; then
  echo "ok   unit tests"
else
  cat "$work/test.log"
  echo "FAIL unit tests"
  status=1
fi
exit $status
