# rontolisp examples

Practical, self-contained rontolisp programs. Unless a directory says otherwise,
everything in it runs identically on the interpreter, the JVM and WASM. Each
directory holds a README of its own; the individual programs explain themselves
in their header comments. How big the compiled artifacts are is measured, not
documented here: [`size-report/`](../size-report).

| Directory | Programs |
| --- | --- |
| [`console/`](console) | Algorithms and console I/O — pure, cross-backend |
| [`ml/`](ml) | Numerical computing and machine learning (arrays, `linalg`, `--simd`, `--gpu`) |
| [`deep-learning-from-scratch/`](deep-learning-from-scratch) | The book *Deep Learning from Scratch* (ゼロから作るDeep Learning) ch02–ch08, ported |
| [`llm/`](llm) | llama2.c's `run.c` ported whole: a Llama 2 inference engine over the real TinyStories checkpoints — the example `--simd` is for |
| [`llm-from-scratch/`](llm-from-scratch) | 『作ってわかる大規模言語モデルの仕組み』 ch2–ch3, ported on the `torch` package: attention, a Transformer, a GPT trained on 漱石 |
| [`net/`](net) | Sockets, HTTP servers and JSON web services |
| [`db/`](db) | PostgreSQL through the real cl-postgres driver and postmodern, up to a REST API |
| [`jvm/`](jvm) | A Java-callable library class, a C library through `cffi`, `java:` interop and Swing GUIs (JVM only) |
| [`macos/`](macos) | A native Cocoa window, the Objective-C runtime, and Metal under it, through the built-in `appkit` / `objc` / `metal` / `scene` packages (macOS only) |
| [`browser/`](browser) | Browser demos: compile to WASM, run in a page |
| [`count-vowels/`](count-vowels), [`wit/`](wit) | Crossing the WASM boundary: exporting to a host, implementing and calling a WIT world, composing with Rust |
| [`asdf/`](asdf) | Loading real third-party libraries with `asdf:load-system` / `ql:quickload` |
| [`wasmcloud/`](wasmcloud), [`cloudflare-workers/`](cloudflare-workers), [`gae/`](gae) | Platform templates |

Assuming the executable JAR is built (`./mvnw clean package`):

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

## Where things run

Most programs run on all four backends (see [Running](#running) below). These
areas need a narrower set:

- **`net/` servers** on WASM need `--component` plus
  `wasmtime run ... -S tcp=y -S inherit-network=y`; the `http-handler` ones run
  under `wasmtime serve`. TLS (`tls-listen`) is interpreter/JVM only.
- **`jvm/`** is JVM-family only: the CFFI bindings need a foreign function API
  neither WASM backend has; the `java:` interop and Swing demos need the JVM and
  a display, and the native binary carries no reflection metadata for them.
- **`macos/`** is macOS, every JVM-side shape of the language (`java -jar`, the
  native binary, a compiled `.class`/`.jar`); the window and Metal examples need
  a display, so they are not in `examples.yaml`. The two that open nothing —
  `objc-runtime.lisp`, `system-frameworks.lisp` — run in a terminal and are
  listed there under `os: [mac]`.

## Guides by area

The examples are the runnable half; each has a guide behind it:

| Topic | Guide |
| --- | --- |
| SIMD / GPU acceleration | [simd-acceleration](../doc/en/guides/simd-acceleration.md), [gpu-acceleration](../doc/en/guides/gpu-acceleration.md) |
| Linear algebra, neural networks (`linalg`, `torch`) | [linear-algebra](../doc/en/guides/linear-algebra.md), [neural-networks](../doc/en/guides/neural-networks.md) |
| Web: Clack, serving HTTP | [clack](../doc/en/guides/clack.md), [http-handler](../doc/en/guides/http-handler.md) |
| JVM: calling library classes, C libraries, Java interop | [jvm-library](../doc/en/guides/jvm-library.md), [cffi](../doc/en/guides/cffi.md), [java-interop](../doc/en/guides/java-interop.md) |
| macOS GUI and 3-D (`appkit`, `objc`, `metal`, `scene`) | [objc-appkit](../doc/en/guides/objc-appkit.md), [solid-modeling](../doc/en/guides/solid-modeling.md) |
| Testing with rove | [testing](../doc/en/guides/testing.md) |

## Running

Any example can be interpreted, compiled to a JVM `.class`, or compiled to
`.wasm`:

```bash
# 1. Interpreter
java -jar $JAR examples/console/nqueens.lisp

# 2. JVM (the class is named after the output file, so keep it path-free)
java -jar $JAR examples/console/nqueens.lisp -o Prog.class && java Prog

# 3. WASM (requires wasmtime 14+)
java -jar $JAR examples/console/nqueens.lisp -o nqueens.wasm && wasmtime run nqueens.wasm
```

Programs that touch files need a preopened directory on WASM:

```bash
java -jar $JAR examples/console/line-numbers.lisp -o ln.wasm
wasmtime run --dir . ln.wasm
```

The macOS GUI and Metal examples run the same ways on a Mac; a representative
set:

```bash
java -jar $JAR examples/macos/counter.lisp
java -jar $JAR examples/macos/menubar.lisp              # no window; look at the menu bar
java -jar $JAR examples/macos/metal-cube.lisp
java -jar $JAR examples/macos/scene-robot-arm.lisp
./target/rontolisp examples/macos/counter.lisp         # the native binary, after ./mvnw -Pnative package
java -jar $JAR examples/macos/counter.lisp -o Counter.class && java Counter
```

## An example that checks itself

Most examples print a result and leave the checking to
[`examples.yaml`](examples.yaml). Three do it in the Lisp instead, with
[rove](../doc/en/guides/testing.md) — the shape to copy when what you are
writing has a right answer rather than only an output:
`console/roman.lisp` asserts the full 1..3999 Roman-numeral round-trip,
`cloudflare-workers/httpbin/check.lisp` asserts the Worker's parsed reply, and
`browser/minesweeper/minesweeper-core-test.lisp` checks a GUI example's
rendering-free core head-less.

The recipe is a few lines. Load rove, silence its ANSI colors, write `deftest`s,
and make the verdict the exit code:

```lisp
(asdf:load-system :rove)
(use-package :rove)
(setf *enable-colors* nil)

(deftest arithmetic
  (testing "adding two integers"
    (ok (= (add 1 2) 3))))

(uiop:quit (if (run-suite *package*) 0 1))
```

rove is vendored in this repository, so its three directories go on
`--system-path` and nothing is downloaded; outside it, `(ql:quickload "rove")`
fetches the same sources. The systems are spliced in at compile time, so the
compiled class / module is self-contained:

```bash
SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
java -jar $JAR examples/console/roman.lisp --system-path $SP
```

## Verifying every non-GUI example at once

[`examples.yaml`](examples.yaml) lists each non-GUI example and the backends it
can be verified on; [`ExamplesE2eTest`](../src/test/java/am/ik/rontolisp/e2e/ExamplesE2eTest.java)
turns every *(example × backend)* pair into one dynamic test. `interpreter` /
`jvm` / `wasm` run the program and check its output; `jvm-compile` /
`wasm-component` / `no-gc` only build it, because blocking servers and
host-invoked modules never return on their own.

Each entry declares its `args` / `stdin` and one `expect`: `equals` (stdout
matches this text), `file` (matches a file under `examples/`), `contains`
(every listed substring appears) or `skip: true`. Omitting `expect` means "exit
0 and non-empty output". `equals`/`file` are checked against **all** run
backends, so a per-backend divergence is a real failure. An example that loads
an ASDF system names its directory — or, like the rove ones above, the LIST of
directories — under `systemPath`. One that can only *run* on a given platform
names it under `os` (`os: [mac]`): that gates the run legs and leaves the
compile legs alone, so its lowering is still checked everywhere.

The suite is opt-in, so a plain `./mvnw test` skips it:

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
```

To run against the native binary instead, pass
`-Drontolisp.binary="$PWD/target/rontolisp"` and drop `-Drontolisp.examples`.
The full suite takes minutes; narrow it with a comma-separated
`-Drontolisp.examples.only=<substrings>` matched against the manifest path
(`console/,ml/`). A pattern matching nothing produces `Tests run: 0` rather than
the whole suite.

Adding an example means appending an entry to `examples.yaml` — no Java changes.
To regenerate an externalised expected file, run the example and save its
stdout. GUI examples (`jvm/`, `macos/` and the `browser/` demos) are excluded:
they open a window or run in a page and cannot be checked headless — though the
part of one that is not GUI can be, which is what
`minesweeper-core-test.lisp` and the terminal-only `macos/` programs are.
