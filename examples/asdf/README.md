# Loading real ASDF libraries

These demos load REAL third-party Common Lisp libraries -- unmodified
upstream sources -- through `asdf:load-system` and exercise their public API.
All run identically on all four backends (interpreter, JVM, WASM Preview 1
and `--component`); they are the programs the cross-backend E2E tests pin
(`SplitSequenceE2eTest` / `ParseNumberE2eTest` / `ClUtilitiesE2eTest` /
`ClWhoE2eTest`).

| Demo | Library | Upstream |
| --- | --- | --- |
| [`split-sequence-demo.lisp`](split-sequence-demo.lisp) | split-sequence v2.0.1 (MIT) | <https://github.com/sharplispers/split-sequence> |
| [`parse-number-demo.lisp`](parse-number-demo.lisp) | parse-number v1.8 (BSD 3-Clause) | <https://github.com/sharplispers/parse-number> |
| [`cl-utilities-demo.lisp`](cl-utilities-demo.lisp) | cl-utilities v1.2.4 (public domain) | <https://common-lisp.net/project/cl-utilities/> |
| [`cl-who-demo.lisp`](cl-who-demo.lisp) | cl-who v1.1.5 (BSD 2-Clause) | <https://github.com/edicl/cl-who> |

## Where the libraries come from

The library sources are vendored in this repository for the test suite, so
the demos run out of the box from the repository root:

- `src/test/resources/split-sequence/`
- `src/test/resources/parse-number/`
- `src/test/resources/cl-utilities/`
- `src/test/resources/cl-who/`

Alternatively, download the same versions from upstream and point
`--system-path` (or the `RONTOLISP_SOURCE_REGISTRY` environment variable) at
the directory containing the `.asd` file:

```bash
curl -sL https://github.com/sharplispers/split-sequence/archive/refs/tags/v2.0.1.tar.gz | tar xz
curl -sL https://github.com/sharplispers/parse-number/archive/refs/tags/v1.8.tar.gz | tar xz
curl -sL https://common-lisp.net/project/cl-utilities/cl-utilities-latest.tar.gz | tar xz
```

## Running (all four backends)

From the repository root, using the split-sequence demo (substitute
`parse-number-demo.lisp` and `src/test/resources/parse-number` for the
other). `rontolisp` is the native binary (`./mvnw -Pnative clean package
-DskipTests`); `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar` works
identically everywhere `rontolisp` appears:

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
  wasmtime run -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    demo-comp.wasm
```

The compile path splices the system's component files in at compile time
(the `.asd` must be on disk when compiling), so the produced `.class`/`.wasm`
is self-contained -- running it needs no library files.

## Expected output

`split-sequence-demo.lisp`:

```console
("a" "b" "" "c")
("a" "b" "c")
((1 2) (4 5) (6))
((1) (3) (5))
((1) (3) (5))
("hello" "world" "lisp")
16
("a" "b")
("c" "d")
("b" "c" "d")
("a" "b")
((1) (3) (4))
("" "" "b" "c")
```

`parse-number-demo.lisp`:

```console
42
-13
3.14
1/3
-1/2
1000.0
250.0
5.0
255
5
511
5
-42.5
17
```

`cl-utilities-demo.lisp`:

```console
("a" "b" "" "c")
("a" "b" "c")
((1) (3) (5))
((1) (3) (5))
1
9
(3 . "three")
1
1
(1 1 1)
(1 1 2)
(5 t (#\h #\e #\l #\l #\o))
24
49
(0 1 4 9 16)
((2 4 6) (1 3 5))
1
1
(2 1)
42
8
255
8
(1 99)
42
(2 5)
```

`cl-who-demo.lisp`:

```console
<html><head><title>Hi</title></head><body><p>Hello<a href='/x'>link</a></p></body></html>
<div><span>3</span><span>&lt;a&amp;b&gt;</span><span>3-4</span></div>
<br />
<br>
<p>&#xe9;</p>
```

## What can be loaded today

A library qualifies when it stays inside plain
`defun`/`defmacro`/`defpackage` code, `loop`, multiple values,
`check-type`/`etypecase` with the supported type specifiers, declarations
(parsed no-ops) and the lite `define-condition`/`make-condition`/`warn`/
`restart-case`/`return-from` idioms. Libraries built on CLOS, the
condition/restart system, dynamic (special) variable binding or mutable
strings do not load yet -- see the
[ASDF systems guide](../../doc/en/guides/asdf-systems.md) for the supported
subset.
