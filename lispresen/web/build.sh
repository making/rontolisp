#!/usr/bin/env bash
# Compiles lispresen-web.lisp to lispresen.wasm, the --no-wasi reactor index.html loads.
# Serve this directory over HTTP (jwebserver) and open index.html.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${RONTOLISP_JAR:-$root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar}
java -jar "$jar" "$here/lispresen-web.lisp" -o "$here/lispresen.wasm" --no-wasi --optimize
