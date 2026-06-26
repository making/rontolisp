#!/usr/bin/env bash
# Recompile the .lisp sources in this directory to .wasm using rontolisp.
# Run from the repository root or from this directory; it locates the JAR
# relative to the repo root.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

for src in "$here"/*.lisp; do
  name="$(basename "$src" .lisp)"
  echo "compiling $name.lisp -> $name.wasm"
  java -jar "$jar" "$src" -o "$here/$name.wasm"
done

echo "done. Serve this directory over http, e.g.:"
echo "  python3 -m http.server 8000 --directory \"$here\""
