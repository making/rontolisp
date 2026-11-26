#!/usr/bin/env bash
# Compile the Worker's module. There is no Java in this directory and that is
# the point: the program is examples/java/hello-mvcfn's HelloMvcfn.java ITSELF
# -- a WebMvc.fn route behind a DispatcherServlet, the same file a JDK runs
# (on webmvc-fn-lite or on Spring Framework), that binds a socket on the
# interpreter, the JVM and in a native executable, and that serves under
# `wasmtime serve` -- compiled for a host that CALLS an export instead of
# handing over a socket. Its `main` runs once, when the instance starts:
# ServletHttpServer's start() hands the application to the reactor instead of
# binding the port, and the compiler synthesizes the `handle-request` export
# src/index.js calls.
#
# --no-wasi: the Worker calls the exported entry point directly, so the module
#   needs no WASI imports at all. It becomes a reactor: `_initialize` instead
#   of `_start`. System.getenv("PORT") answers null here, and the port the
#   program names is the one nobody binds.
# --optimize=size: a Worker bundle has a size limit; the tree-shaker keeps what
#   the program reaches of the libraries, and the =size level declines the two
#   speed-over-size wasm-GC emissions.
# --host-output: what the program prints is the Worker's log -- standard
#   output to console.log, standard error to console.error, written by the glue
#   below -- so the report of what ended an instance (an exception main left
#   uncaught, a wait refused, System.exit) is seen, not only the trap.
# The glue: src/worker.js, written beside the module -- the host half of this
#   boundary, the Request -> envelope -> Response mapping included.
#   src/worker.js is CHECKED IN and pinned by HostGlueEmitterTest, so regenerate
#   it here rather than editing it.
# --java-source-path / --java-source-dep: the libraries, read as source -- the
#   container and webmvc-fn-lite from this checkout, the Servlet API's sources
#   jar from the local Maven repository or Maven Central.
# --emit-worker-entry: write src/worker.js as --emit-js-glue does (the flag
#   implies it), and src/index.js beside it -- the Worker's three lines,
#   importing the module and the glue and exporting worker(module). src/index.js
#   is written ONCE: a later build leaves one that is there alone, so it is
#   yours to edit (a remoteAddr, a host of your own).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"

jar="$repo_root/target/rontolisp-0.1.0-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo "JAR not found: $jar" >&2
  echo "Build it first from the repo root: ./mvnw clean package" >&2
  exit 1
fi

echo "compiling ../../java/hello-mvcfn/src/main/java/HelloMvcfn.java -> src/worker.wasm + src/worker.js + src/index.js"
java -jar "$jar" "$repo_root/examples/java/hello-mvcfn/src/main/java/HelloMvcfn.java" \
  --java-source-path "$repo_root/servlet-httpserver/src/main/java:$repo_root/webmvc-fn-lite/src/main/java" \
  --java-source-dep jakarta.servlet:jakarta.servlet-api:6.1.0 \
  -o "$here/src/worker.wasm" --no-wasi --host-output --optimize=size --emit-worker-entry

ls -l "$here/src/worker.wasm" "$here/src/worker.js" "$here/src/index.js"
echo "done. Run it with:  npx wrangler dev"
