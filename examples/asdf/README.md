# Loading real ASDF libraries

These demos load REAL third-party Common Lisp libraries -- unmodified
upstream sources -- through `asdf:load-system` and exercise their public API.
All of them run identically on all four backends (interpreter, JVM,
WASM Preview 1 and `--component`); they are the programs the cross-backend E2E
tests pin (`SplitSequenceE2eTest` / `ParseNumberE2eTest` / `ClUtilitiesE2eTest`
/ `ClWhoE2eTest` / `AssocUtilsE2eTest` / `ClBase64E2eTest` / `JzonE2eTest`
/ `Md5E2eTest` / `ClPpcreE2eTest` / `IroncladE2eTest` / `Uax15E2eTest`).
jzon's three numeric leaf components (the eisel-lemire float reader and
Schubfach float printer) are replaced at load time by built-in shims over
rontolisp's native float arithmetic, so float text takes rontolisp's
cross-backend-identical shape rather than Schubfach's shortest-round-trip
string:

```console
rontolisp examples/asdf/jzon-demo.lisp --system-path src/test/resources/jzon/src
```

| Demo | Library | Upstream |
| --- | --- | --- |
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

## Where the libraries come from

The library sources are vendored in this repository for the test suite, so
the demos run out of the box from the repository root:

- `src/test/resources/split-sequence/`
- `src/test/resources/parse-number/`
- `src/test/resources/cl-utilities/`
- `src/test/resources/cl-who/`
- `src/test/resources/assoc-utils/`
- `src/test/resources/cl-base64/`
- `src/test/resources/jzon/` (the `.asd` lives in its `src/` subdirectory)
- `src/test/resources/md5/`
- `src/test/resources/cl-ppcre/`
- `src/test/resources/ironclad/` (the SHA-256/HMAC/PBKDF2/HKDF/SCRAM slice only; its executable
  `ironclad.asd` is kept for provenance but a bundled replacement is what loads)
- `src/test/resources/uax-15/` (the only demo whose library depends on others, so its
  `--system-path` also needs `src/test/resources/split-sequence` and
  `src/test/resources/cl-ppcre`)

Alternatively, download the same versions from upstream and point
`--system-path` (or the `RONTOLISP_SOURCE_REGISTRY` environment variable) at
the directory containing the `.asd` file:

```bash
curl -sL https://github.com/sharplispers/split-sequence/archive/refs/tags/v2.0.1.tar.gz | tar xz
curl -sL https://github.com/sharplispers/parse-number/archive/refs/tags/v1.8.tar.gz | tar xz
curl -sL https://common-lisp.net/project/cl-utilities/cl-utilities-latest.tar.gz | tar xz
curl -sL https://github.com/fukamachi/assoc-utils/archive/refs/heads/master.tar.gz | tar xz
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
  wasmtime run -W gc=y demo-comp.wasm
```

`--system-path` takes ONE value, a `:`-joined list of directories, so a library
with dependencies of its own names them all in the same argument. uax-15 is the
only demo here that needs it:

```bash
SYS=src/test/resources/uax-15:src/test/resources/split-sequence:src/test/resources/cl-ppcre
rontolisp examples/asdf/uax-15-demo.lisp --system-path $SYS
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

`assoc-utils-demo.lisp`:

```console
"eitaro"
"none"
("name" "loc")
("eitaro" "vienna")
(:NAME "eitaro" :LOC "vienna")
(("name" . "eitaro") ("loc" . "vienna"))
(("name" . "eitaro"))
(("y" . 2))
(("k" . "v"))
"eitaro in vienna"
42
"equal"
```

`jzon-demo.lisp`:

```console
42
-1.5
"hello"
t
null
#(1 2 3)
"rontolisp"
#("lisp" "wasm")
[1,2,3]
{"a":1}
{"k":[true,null,7]}
```

`uax-15-demo.lisp`:

```console
(197)
(65 778)
(49 49 8260 50)
(102 102)
(197)
230
(1231 (832 NIL) (71984 T))
(T T NIL T)
```

## What can be loaded today

A library qualifies when it stays inside plain
`defun`/`defmacro`/`defpackage` code, `loop`, multiple values,
`check-type`/`etypecase` with the supported type specifiers, declarations
(parsed no-ops) and the lite `define-condition`/`make-condition`/`warn`/
`restart-case`/`return-from` idioms. Libraries built on the CLOS static subset, the lite condition system,
dynamic (special) variable binding, Gray output streams and adjustable
fill-pointered string buffers load too (jzon exercises all of these on
every backend); restarts remain out of reach -- see the
[ASDF systems guide](../../doc/en/guides/asdf-systems.md) for the supported
subset.
