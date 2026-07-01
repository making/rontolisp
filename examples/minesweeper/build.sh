#!/usr/bin/env bash
# Recompile minesweeper.lisp to a browser-loadable WebAssembly reactor.
# The --no-wasi flag drops all WASI imports and exports _initialize, so the
# module instantiates with an empty import object and runs with just
# WebAssembly GC support -- no shim, no server-side runtime.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling minesweeper.lisp -> minesweeper.wasm"
java -jar "$jar" "$here/minesweeper.lisp" -o "$here/minesweeper.wasm" --no-wasi

echo "done. Serve this directory over http, e.g.:"
echo "  jwebserver -p 8000 --directory \"$here\""
echo "then open http://localhost:8000/minesweeper.html"
