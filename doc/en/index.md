# rontolisp

![rontolisp logo](../assets/rontolisp-logo.png)

rontolisp is a set of language front ends and execution backends joined by one
intermediate language: S-expressions. Each front end reads its source and lowers it to the
same core Lisp forms; macro expansion and the compile passes run on those forms, and each
backend takes them from there. No backend knows a front end's names.

```text
 .lisp  Common Lisp --+                           +-- Interpreter (REPL)
 .scm   Scheme      --+                           +-- JVM: .class / .jar
 .clj   Clojure     --+--> S-expressions ---------+-- WASM (wasm-GC): WASI Preview 1 module,
 .star  Starlark    --+    (core Lisp forms)      |     WASI 0.3 component
 .java  Java        --+                           +-- WASM (--no-gc): MVP module
                                                  +-- Native executable (--native)
```

Common Lisp is the IR's own language and the most complete front end; the others are
experimental. It is all written in Java with no external dependencies in the core.

## Front ends

| Language | Files | |
| --- | --- | --- |
| Common Lisp | `.lisp` | A subset of ANSI Common Lisp: the [Language Reference](reference/data-types.md), the [Guides](guides/asdf-systems.md) and what is [missing](guides/missing-features.md) |
| [Scheme](scheme/index.md) (experimental) | `.scm` | A subset of R7RS-small |
| [Clojure](clojure/index.md) (experimental) | `.clj`, `.cljc` | A small subset; `deps.edn` projects, nREPL, Ring |
| [Starlark](starlark/index.md) (experimental) | `.star` | The Python dialect of Bazel's build files |
| [Java](java/index.md) (experimental) | `.java` | A subset of the language with its own classes (no JDK code); Servlet, JDBC |

The language is picked per file by its extension (`--source-language` overrides it), so one
program may mix languages file by file. Where a pair does not combine: `--no-gc` takes only
a restricted subset of Common Lisp, and Clojure's Java interop runs on the interpreter and
the JVM only.

## Backends

- **Interpreter** -- tree-walking evaluation with a [REPL](getting-started/repl.md); the
  default when a [file is run](getting-started/file-interpretation.md), built for
  turnaround rather than throughput (loop-heavy code runs 20x-200x faster compiled).
  [SWANK](getting-started/swank.md) (SLIME, SLY, Lem) for Common Lisp and
  [nREPL](clojure/nrepl.md) for Clojure
- **[JVM](compiling/jvm.md)** -- a `.class` or an executable `.jar` runnable on any JRE
- **[WebAssembly](compiling/wasm.md)** -- wasm-GC output as a
  [WASI Preview 1 core module](guides/wasm-gc-module.md) or a
  [WASI 0.3 component](guides/wasm-component.md) that implements or calls
  [WIT worlds](guides/wit-contracts.md), plus a [`--no-gc`](guides/wasm-nogc.md) MVP
  module for runtimes without wasm-GC
- **[Native executable](compiling/native.md)** -- `--native` precompiles the WASM output
  with wasmtime into one self-contained executable that needs neither wasmtime nor a JVM

Besides running and compiling, the `rontolisp` command [formats source](getting-started/format.md)
and runs a test suite with its verdict as the exit code: [rove](guides/testing.md) for
Common Lisp, [`clojure.test`](clojure/reference/test.md) for a `deps.edn` project and
[JUnit Jupiter tests](java/testing.md) for a Java program. The
[Maven plugin](compiling/maven-plugin.md) does the same inside a Maven build: it compiles
`src/main/lisp` into the project's jar, compiles a Java program to WebAssembly and runs the
tests.
[Build & Install](getting-started/build.md) covers the prebuilt binaries and building from
source.

## Try it

The examples on these pages run in your browser via the same WebAssembly build as the
[playground](../../playground.html):

```lisp
(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
(print (fact 10))
```

Press **Run** above (the rontolisp runtime loads on the first run).

Working with an AI coding agent? These same pages are published as an
[agent skill](getting-started/agent-skill.md), regenerated on every deploy.
