#!/usr/bin/env bash
# Compiles lispresen-web.lisp to lispresen.wasm, the --no-wasi reactor index.html loads.
# Serve the directory over HTTP (jwebserver) and open index.html.
#
#   lispresen/web/build.sh        # lispresen.wasm here, beside index.html
#   lispresen/web/build.sh DIR    # the editor, index.html and lispresen.wasm, in DIR
#
# The second form is what the Pages workflow publishes as lispresen/editor/, the page
# the intro deck frames.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
version=$(awk -F '[<>]' '/<version>/ { print $3; exit }' "$root/pom.xml")
jar=${RONTOLISP_JAR:-$root/target/rontolisp-$version-exec.jar}
out=${1:-$here}
mkdir -p "$out"
java -jar "$jar" "$here/lispresen-web.lisp" -o "$out/lispresen.wasm" --no-wasi --optimize
[[ $out -ef $here ]] || cp "$here/index.html" "$out/index.html"
