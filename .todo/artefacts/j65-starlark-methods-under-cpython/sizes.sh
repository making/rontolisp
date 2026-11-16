#!/bin/bash
# usage: sizes.sh jar outdir : sizes of a fixed set of programs
JAR=$1; O=$2; mkdir -p $O; R=${REPO:?the repository root}
c() { n=$1; shift; (cd $(dirname $1) && java -jar $JAR $(basename $1) -o $O/$n.wasm "${@:2}" >/dev/null 2>$O/$n.err) ; printf "%s %s\n" $n $(stat -c %s $O/$n.wasm 2>/dev/null); }
c gateway $R/examples/proxy-wasm/gateway.lisp --no-wasi --optimize
c gateway-size $R/examples/proxy-wasm/gateway.lisp --no-wasi --optimize=size
c http_headers-size $R/examples/proxy-wasm/sdk-examples/http_headers.lisp --no-wasi --optimize=size
c hello $R/size-report/programs/hello_world/hello_world.lisp
c pi $R/size-report/programs/pi_approx/pi_approx.lisp
c zlib $R/size-report/programs/zlib/zlib.lisp
c zlib-size $R/size-report/programs/zlib/zlib.lisp --optimize=size
c sort $R/bench-report/programs/sort.lisp
c string $R/bench-report/programs/string.lisp
c hash $R/bench-report/programs/hash.lisp
c clos $R/bench-report/programs/clos.lisp
c targets $R/examples/starlark/targets.star
c targets-size $R/examples/starlark/targets.star --optimize=size
c methods $R/.todo/artefacts/j54-starlark-faster-than-cpython/methods.star
c dicts $R/.todo/artefacts/j54-starlark-faster-than-cpython/dicts.star
c loop $R/.todo/artefacts/j54-starlark-faster-than-cpython/loop.star
