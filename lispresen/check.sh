#!/usr/bin/env bash
# Verifies lispresen: renders examples/intro.lisp with --embed (its image sits beside it)
# on the interpreter, the JVM, WASM, the WASM component, a native executable and SBCL,
# requires each output to equal examples/intro.html and the page image it draws to equal
# examples/intro.png byte for byte and the deck to raise no diagnostic, --version to print
# the pom.xml version and the commit checked out, and the starter deck `new` writes to be
# the same on every leg but for its date and to render without a diagnostic under
# --strict, and `serve` to answer the deck's page (the same on every leg that serves),
# its build version, its image and a byte range of its video, where the two WASM legs
# refuse it; then runs the unit tests on the interpreter. SBCL is the reference Common Lisp: lispresen is written
# in portable Common Lisp, with rontolisp-only code behind #+rontolisp.
#
#   lispresen/check.sh            # verify
#   lispresen/check.sh --update   # rewrite the golden HTML and PNG from the interpreter first
#   lispresen/check.sh --exe PATH # verify only the lispresen executable at PATH (no jar needed)
#
# Needs the executable jar (./mvnw package; RONTOLISP_JAR overrides its path), java,
# wasmtime, curl and python3 (a free port); the SBCL leg is skipped when sbcl is not on
# PATH. The unit tests stay on
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

# What --version prints: the version from pom.xml and the commit read when lispresen was
# built, here the one checked out.
version=$(awk -F '[<>]' '/<version>/ { print $3; exit }' "$root/pom.xml")
commit=$(git -C "$root" rev-parse HEAD 2> /dev/null | cut -c 1-7)
expected_version="{\"version\": \"$version\", \"gitCommit\": \"${commit:-unknown}\"}"

status=0
check() {
  local name=$1
  shift
  # The deck comes from standard input, so its files resolve against the working directory
  # and the page image is written there: each leg runs in its own copy of examples/.
  local dir=$work/$name
  cp -R "$here/examples" "$dir"
  rm -f "$dir/intro.png"
  local failed= log=$work/$name.log
  : > "$log"
  if ! { (cd "$dir" && "$@" - -o - --embed < "$deck") > "$work/$name.html" 2> "$work/$name.err" \
      && [[ ! -s $work/$name.err ]] && cmp -s "$golden" "$work/$name.html" \
      && cmp -s "$golden_png" "$dir/intro.png"; }; then
    failed=1
    { cat "$work/$name.err"
      cmp "$golden" "$work/$name.html" || true
      cmp "$golden_png" "$dir/intro.png" || true; } >> "$log"
  fi
  local printed
  printed=$("$@" --version 2>&1) || true
  if [[ $printed != "$expected_version" ]]; then
    failed=1
    echo "--version printed $printed, not $expected_version" >> "$log"
  fi
  # The starter deck, its date masked; the first leg's is the one the others must equal.
  if (cd "$dir" && "$@" new starter.lisp && "$@" starter.lisp --check --strict) > "$work/$name.new.err" 2>&1 \
      && [[ ! -s $work/$name.new.err ]]; then
    sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}/YYYY-MM-DD/' "$dir/starter.lisp" > "$work/$name.starter.lisp"
    [[ -f $work/starter.lisp ]] || cp "$work/$name.starter.lisp" "$work/starter.lisp"
    if ! cmp -s "$work/starter.lisp" "$work/$name.starter.lisp"; then
      failed=1
      diff "$work/starter.lisp" "$work/$name.starter.lisp" >> "$log" || true
    fi
  else
    failed=1
    { echo "the starter deck:"; cat "$work/$name.new.err"; } >> "$log"
  fi
  # serve: a WASM module accepts no connection, so those two legs must refuse it; every
  # other leg serves the page (the first one's is the one the others must equal), the
  # build's version, the image the page names and a byte range of the video (206), until
  # it is stopped.
  case $name in
    wasm | wasm-component)
      if (cd "$dir" && "$@" serve intro.lisp) > "$work/$name.serve.err" 2>&1 \
          || ! grep -q 'serve needs a server socket' "$work/$name.serve.err"; then
        failed=1
        { echo "serve was not refused:"; cat "$work/$name.serve.err"; } >> "$log"
      fi
      ;;
    *)
      local port pid url
      port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
      url=http://127.0.0.1:$port
      (cd "$dir" && exec "$@" serve intro.lisp --port "$port") > "$work/$name.serve.err" 2>&1 &
      pid=$!
      if curl -fs --retry 120 --retry-connrefused --retry-delay 1 -o "$work/$name.served.html" "$url/" \
          && [[ $(curl -fsS "$url/__lispresen/version") == 1 ]] \
          && curl -fsS -o "$work/$name.served.png" "$url/pipeline.png" \
          && cmp -s "$here/examples/pipeline.png" "$work/$name.served.png" \
          && [[ $(curl -fsS -r 100-199 -o "$work/$name.range" -w '%{http_code}' "$url/demo.mp4") == 206 ]] \
          && cmp -s "$work/$name.range" <(head -c 200 "$here/examples/demo.mp4" | tail -c 100) \
          && grep -q "/__lispresen/version" "$work/$name.served.html"; then
        [[ -f $work/served.html ]] || cp "$work/$name.served.html" "$work/served.html"
        if ! cmp -s "$work/served.html" "$work/$name.served.html"; then
          failed=1
          echo "the served page differs from the first leg's" >> "$log"
        fi
      else
        failed=1
        { echo "serve:"; cat "$work/$name.serve.err"; } >> "$log"
      fi
      kill "$pid" 2> /dev/null || true
      wait "$pid" 2> /dev/null || true
      ;;
  esac
  if [[ -z $failed ]]; then
    echo "ok   $name"
  else
    echo "FAIL $name"
    cat "$log"
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
