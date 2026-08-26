#!/usr/bin/env bash
# Compile the App Engine application. There is no .lisp file in this directory
# and that is the point: the program is examples/net/httpbin-clack.lisp ITSELF
# -- the same file that binds a socket locally, deploys as a Servlet war and
# runs under `wasmtime serve` -- compiled for the host that gives a program a
# port and asks it to listen.
#
# -o app.jar: an executable jar, Main-Class App. That is the shape App Engine's
#   Java buildpack detects and the ONLY one it accepts without an
#   appengine-web.xml; see ../README.md. The runtime's copies travel inside it,
#   so the jar is the whole deployment.
#
# App Engine sets PORT=8081, and the program's (uiop:getenvp "PORT") reads it.
# Nothing here names App Engine.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling ../../net/httpbin-clack.lisp -> app.jar"
java -jar "$jar" "$repo_root/examples/net/httpbin-clack.lisp" -o "$here/app.jar"

ls -l "$here/app.jar"
echo "done. Deploy it with:  gcloud app deploy $here/app.yaml --project YOUR_PROJECT"
