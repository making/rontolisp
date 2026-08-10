# Loading real ASDF libraries

These demos load REAL third-party Common Lisp libraries — unmodified upstream
sources — through `asdf:load-system` and exercise their public API. All run
identically on all four backends (interpreter, JVM, WASM Preview 1,
`--component`), and each is pinned by its own cross-backend E2E test.

| Demo | Library | Upstream |
| --- | --- | --- |
| [`alexandria-demo.lisp`](alexandria-demo.lisp) | alexandria 1.0.1 (public domain / 0-clause MIT) | <https://gitlab.common-lisp.net/alexandria/alexandria> |
| [`split-sequence-demo.lisp`](split-sequence-demo.lisp) | split-sequence v2.0.1 (MIT) | <https://github.com/sharplispers/split-sequence> |
| [`parse-number-demo.lisp`](parse-number-demo.lisp) | parse-number v1.8 (BSD 3-Clause) | <https://github.com/sharplispers/parse-number> |
| [`cl-utilities-demo.lisp`](cl-utilities-demo.lisp) | cl-utilities v1.2.4 (public domain) | <https://common-lisp.net/project/cl-utilities/> |
| [`cl-who-demo.lisp`](cl-who-demo.lisp) | cl-who v1.1.5 (BSD 2-Clause) | <https://github.com/edicl/cl-who> |
| [`assoc-utils-demo.lisp`](assoc-utils-demo.lisp) | assoc-utils (public domain) | <https://github.com/fukamachi/assoc-utils> |
| [`cl-base64-demo.lisp`](cl-base64-demo.lisp) | cl-base64 v3.4 (BSD-style) | <https://github.com/darabi/cl-base64> |
| [`jzon-demo.lisp`](jzon-demo.lisp) | com.inuoe.jzon v1.1.4 (MIT) | <https://github.com/Zulu-Inuoe/jzon> |
| [`md5-demo.lisp`](md5-demo.lisp) | md5 v2.0.4 (public domain) | <https://github.com/pmai/md5> |
| [`cl-ppcre-demo.lisp`](cl-ppcre-demo.lisp) | cl-ppcre v2.1.2 (BSD 2-Clause) | <https://github.com/edicl/cl-ppcre> |
| [`ironclad-demo.lisp`](ironclad-demo.lisp) | ironclad v0.61, SHA-256/HMAC/PBKDF2/HKDF/SCRAM slice (BSD 3-Clause) | <https://github.com/sharplispers/ironclad> |
| [`uax-15-demo.lisp`](uax-15-demo.lisp) | uax-15 v0.1.3 (MIT) | <https://github.com/sabracrolleton/uax-15> |
| [`tiny-routes-demo.lisp`](tiny-routes-demo.lisp) | tiny-routes v0.1.1 (BSD 3-Clause). For a size-constrained module load the opt-in `"tiny-routes/lite"`, which drops the cl-ppcre dependency — see the [asdf-systems guide](../../doc/en/guides/asdf-systems.md) | <https://github.com/jeko2000/tiny-routes> |
| [`clack-hello.lisp`](clack-hello.lisp) | clack v2.1.0 + lack (MIT), served by the built-in `clack-handler-rontolisp` backend; loads via `ql:quickload` (network on the first run) | <https://github.com/fukamachi/clack> |

jzon's three numeric leaf components (the eisel-lemire float reader and
Schubfach float printer) are replaced at load time by built-in shims over
rontolisp's native float arithmetic, so float text takes rontolisp's
cross-backend-identical shape rather than Schubfach's shortest-round-trip
string.

## Where the libraries come from

The sources are vendored under `src/test/resources/<library>/` for the test
suite, so the demos run out of the box from the repository root. Two of them
have a wrinkle: jzon's `.asd` lives in its `src/` subdirectory, and only the
SHA-256/HMAC/PBKDF2/HKDF/SCRAM slice of ironclad is vendored (its executable
`ironclad.asd` is kept for provenance, but a bundled replacement is what loads).

Alternatively, download the same versions from upstream and point
`--system-path` (or the `RONTOLISP_SOURCE_REGISTRY` environment variable) at
the directory containing the `.asd` file:

```bash
curl -sL https://github.com/sharplispers/split-sequence/archive/refs/tags/v2.0.1.tar.gz | tar xz
```

## Running (all four backends)

From the repository root. `rontolisp` is the native binary; `java -jar
target/rontolisp-0.1.0-SNAPSHOT-exec.jar` works identically:

```bash
SYS=src/test/resources/split-sequence

# 1. Interpreter
rontolisp examples/asdf/split-sequence-demo.lisp --system-path $SYS

# 2. JVM (the class is named after the output file, so keep it path-free)
rontolisp examples/asdf/split-sequence-demo.lisp -o Prog.class --system-path $SYS && java Prog

# 3. WASM Preview 1 (requires wasmtime 14+)
rontolisp examples/asdf/split-sequence-demo.lisp -o demo.wasm --system-path $SYS && \
  wasmtime run -W gc demo.wasm

# 4. WASM component / WASI 0.3 (requires wasmtime 46+)
rontolisp examples/asdf/split-sequence-demo.lisp -o demo-comp.wasm --component --system-path $SYS && \
  wasmtime run -W gc=y demo-comp.wasm
```

`--system-path` takes ONE value, a `:`-joined list of directories, so a library
with dependencies of its own names them all in the same argument:

```bash
SYS=src/test/resources/uax-15:src/test/resources/split-sequence:src/test/resources/cl-ppcre
rontolisp examples/asdf/uax-15-demo.lisp --system-path $SYS

SYS=src/test/resources/tiny-routes:src/test/resources/cl-ppcre
rontolisp examples/asdf/tiny-routes-demo.lisp --system-path $SYS
```

The compile path splices the system's component files in at compile time (the
`.asd` must be on disk when compiling), so the produced `.class`/`.wasm` is
self-contained.

A demo using `handler-case`/`unwind-protect` compiles in EH mode, so both wasm
run commands need `-W exceptions=y` (wasmtime 37+). `alexandria-demo.lisp` is
one, and so is `tiny-routes-demo.lisp` (`with-input-from-string` expands to an
`unwind-protect`).

## Expected output

Each demo prints one line per API call it exercises; `split-sequence-demo.lisp`
starts:

```console
("a" "b" "" "c")
("a" "b" "c")
((1 2) (4 5) (6))
```

The output is identical on every backend, which is what the E2E tests assert —
so the demo itself is the specification, and any divergence is a real failure.

## What can be loaded today

A library qualifies when it stays inside plain `defun`/`defmacro`/`defpackage`
code, `loop`, multiple values, `check-type`/`etypecase` with the supported type
specifiers, declarations (parsed no-ops) and the lite
`define-condition`/`make-condition`/`warn`/`restart-case`/`return-from` idioms.
Libraries built on the CLOS static subset, the lite condition system, dynamic
(special) variables, Gray output streams and adjustable fill-pointered string
buffers load too — jzon exercises all of these on every backend. Restarts remain
out of reach; the [ASDF systems guide](../../doc/en/guides/asdf-systems.md) has
the supported subset.
