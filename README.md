# rontolisp

<img src="images/rontolisp-logo.png" alt="rontolisp logo" width="360">

rontolisp is a set of language front ends and execution backends joined by one
intermediate language: S-expressions. Each front end reads its source and lowers it to the
same core Lisp forms; macro expansion and the compile passes run on those forms, and each
backend takes them from there. No backend knows a front end's names.

```
 .lisp  Common Lisp --+                           +-- Interpreter (REPL)
 .scm   Scheme      --+                           +-- JVM: .class / .jar
 .clj   Clojure     --+--> S-expressions ---------+-- WASM (wasm-GC): WASI Preview 1 module,
 .star  Starlark    --+    (core Lisp forms)      |     WASI 0.3 component
 .java  Java        --+                           +-- WASM (--no-gc): MVP module
                                                  +-- Native executable (--native)
```

Common Lisp is the IR's own language and the most complete front end; the others are
experimental. It is all written in Java with no external dependencies in the core: the JVM
and WASM bytecode generators are written from scratch, without ASM or any other code
generation library.

### Front ends

| Language | Files | |
| --- | --- | --- |
| Common Lisp | `.lisp` | A subset of ANSI Common Lisp (partial CLOS, ASDF subset); real libraries such as rove, cl-ppcre and cl-postgres load through `ql:quickload` |
| Scheme (experimental) | `.scm` | A subset of R7RS-small |
| Clojure (experimental) | `.clj`, `.cljc` | A small subset; `deps.edn` projects, nREPL, Ring |
| Starlark (experimental) | `.star` | The Python dialect of Bazel's build files |
| Java (experimental) | `.java` | A subset of the language with its own classes (no JDK code); Servlet, JDBC, Maven plugin |

The language is picked per file by its extension (`--source-language` overrides it), so one
program may mix languages file by file. What a front end needs at run time is library
source spliced into the program like the program's own code. Where a pair does not combine:
`--no-gc` takes only a restricted subset of Common Lisp, and
Clojure's Java interop runs on the interpreter and the JVM only.

### Backends

- **Interpreter** -- tree-walking evaluation with a REPL; SWANK (SLIME, SLY, Lem) for
  Common Lisp and nREPL for Clojure
- **JVM** -- a `.class` or an executable `.jar` runnable on any JRE
- **WebAssembly** -- wasm-GC output as a WASI Preview 1 core module or a WASI 0.3
  (Component Model) component that implements or calls WIT worlds, plus a `--no-gc` MVP
  module for runtimes without wasm-GC
- **Native executable** -- `--native` precompiles the WASM output with wasmtime into one
  self-contained executable that needs neither wasmtime nor a JVM

A component serves `wasi:http` under `wasmtime serve`, and the same programs are deployed
to Cloudflare Workers, wasmCloud, Google App Engine and Envoy (Proxy-Wasm); see
[`examples/`](examples/README.md).

The `rontolisp` command itself runs as an executable JAR or as a GraalVM native image
(prebuilt binaries for macOS and Linux, instant startup). Besides running and compiling, it
formats source (`rontolisp format`) and runs a test suite with its verdict as the exit code
(`rontolisp test`: rove for Common Lisp, `clojure.test` for a `deps.edn` project, JUnit
Jupiter tests for a Java program). In a Maven build,
[`rontolisp-maven-plugin`](rontolisp-maven-plugin/README.md) compiles and tests with rontolisp.

## Documentation

**Full documentation: [https://making.github.io/rontolisp/docs/](https://making.github.io/rontolisp/docs/)**

The manual is organized into Getting Started, Compiling (JVM / WASM / WASI 0.3
components / native executables), a Common Lisp Language Reference (data types, special
forms, macros, `format`, built-in functions, packages, the function namespace), Guides,
and one section per experimental front end (Scheme, Clojure, Starlark, Java). Every
reference page is interactive: the Lisp examples run in your browser via the
same WebAssembly build that powers the playground -- press **Run** on any
example to evaluate it. The Markdown sources live under [`doc/`](doc/) and are
rendered to HTML by the standalone [`docs-tool/`](docs-tool/) generator.

**For AI coding agents: [https://making.github.io/rontolisp/skill/](https://making.github.io/rontolisp/skill/)** --
an agent skill generated from this same documentation on every deploy. It assumes
Common Lisp is known and carries the delta: which operators exist, which Common
Lisp features are missing or partial, the rontolisp-only extensions, and how to
run a program on each backend.

**Try it in your browser: [https://making.github.io/rontolisp/playground.html](https://making.github.io/rontolisp/playground.html)** -- a playground where rontolisp itself runs as WebAssembly. Evaluate expressions in the REPL in any of the five languages, and compile your source to downloadable `.class` and `.wasm` files, entirely client-side. A companion page, [compile & run](https://making.github.io/rontolisp/compile-run.html), compiles a set of Lisp definitions to a WASM module in the browser, then lets you call any function from that module with arguments you supply -- no download, no server. See [`web/README.md`](web/README.md) for how it is built and deployed.

## Requirements

- Java 25+ (for building and running the JAR)
- [GraalVM](https://www.graalvm.org/) 25+ (optional, for native image build)
- [wasmtime](https://wasmtime.dev/) 47+ (for running `.wasm` output, optional; wasm-GC and exception-handling are enabled by default)

## Install

Download a prebuilt native-image binary from the
[releases page](https://github.com/making/rontolisp/releases/tag/0.1.0-SNAPSHOT)
(macOS arm64, Linux amd64/arm64). For example, on macOS (Apple Silicon):

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-darwin-arm64
chmod +x rontolisp-darwin-arm64
sudo mv rontolisp-darwin-arm64 /usr/local/bin/rontolisp
```

Or build from source (produces `target/rontolisp-0.1.0-SNAPSHOT-exec.jar`):

```bash
./mvnw clean package
```

Build a native executable with GraalVM yourself (`target/rontolisp`, instant startup;
with Rust 1.97+ installed it also carries the `--native` precompiler):

```bash
./mvnw -Pnative clean package
```

See [Build & Install](https://making.github.io/rontolisp/docs/en/getting-started/build.html) for details.

## Quick Start

The same source runs on every backend:

```bash
echo '(print (+ 1 2))' > hello.lisp

rontolisp                                # REPL
rontolisp hello.lisp                     # interpret           -> 3
rontolisp -e '(print (+ 1 2))'           # interpret this argument (--eval) -> 3
rontolisp hello.lisp -o Hello.class && java Hello              # JVM -> 3
rontolisp hello.lisp -o hello.wasm && wasmtime run hello.wasm  # WASM -> 3
rontolisp hello.lisp -o hello-c.wasm --component && wasmtime run hello-c.wasm  # WASI 0.3 component -> 3
rontolisp hello.lisp --native -o hello && ./hello              # native executable -> 3

rontolisp format hello.lisp               # re-indent (a directory works too)
rontolisp test tests/main.lisp            # run a rove suite; the exit code is the verdict
```

The other languages run the same way, by extension:

```bash
rontolisp hello.scm                                # Scheme
rontolisp hello.clj -o Hello.class && java Hello   # Clojure on the JVM
rontolisp hello.star -o hello.wasm && wasmtime run hello.wasm  # Starlark on WASM
rontolisp Hello.java --native -o hello && ./hello  # Java as a native executable
rontolisp -e 'print(1 + 2)' --source-language starlark
```

For the REPL, file interpretation, JVM/WASM compilation (including
`rontolisp:wasm-export`, `rontolisp:wasm-import`, `rontolisp:wit-export` with
`--scaffold-wit` (implement a WIT world), `--no-wasi`, `--optimize=off`/`=size`,
`--component`, and `--dynamic`), the `format` source formatter, the `test`
rove runner, the full language reference, and the `rontolisp` extensions (`fetch`), see the
[documentation site](https://making.github.io/rontolisp/docs/).

## Project Structure

```
am.ik.rontolisp              -- Lisp data types (sealed interface)
am.ik.rontolisp.reader       -- Common Lisp reader
am.ik.rontolisp.scheme       -- Scheme front end
am.ik.rontolisp.clojure      -- Clojure front end
am.ik.rontolisp.starlark     -- Starlark front end
am.ik.rontolisp.java         -- Java front end
am.ik.rontolisp.macro        -- Macro expansion
am.ik.rontolisp.eval         -- Tree-walking interpreter, source-language seam
am.ik.rontolisp.compiler     -- Shared compiler interface and analyses
am.ik.rontolisp.codegen.jvm  -- JVM .class generation
am.ik.rontolisp.codegen.wasm -- WASM .wasm generation (wasm-GC, no-GC, WASI, components)
am.ik.rontolisp.runtime      -- Runtime support classes
am.ik.rontolisp.format       -- Source formatter (rontolisp format)
am.ik.rontolisp.cli          -- REPL + CLI entry point
am.ik.jvm                    -- JVM bytecode primitives
am.ik.wasm                   -- WASM binary primitives
am.ik.wit                    -- WIT parser
```

The native-executable runner is
built from [`rontolisp-native/`](rontolisp-native/). The Maven plugin, which compiles
`src/main/lisp` into a project's jar and a Java project to WebAssembly and runs the tests,
lives in [`rontolisp-maven-plugin/`](rontolisp-maven-plugin/README.md).

The documentation site generator is a separate Maven project under
[`docs-tool/`](docs-tool/) (it converts [`doc/`](doc/) Markdown to the published
HTML and depends on flexmark, kept out of the dependency-free core).

## Testing

```bash
./mvnw test
```

The test suite includes:

- **Unit tests** -- Reader, evaluator, and environment (lexer tokenization, parsing, expression evaluation)
- **JVM compiler tests** -- Compiles Lisp, loads the generated `.class` via `URLClassLoader`, runs it, and verifies stdout
- **WASM compiler tests** -- Verifies binary structure (magic number, sections, GC instructions)
- **WASM integration tests** -- Compiles Lisp to `.wasm` and runs it with wasmtime inside a Docker container via [Testcontainers](https://testcontainers.com/). Requires Docker; skipped automatically if Docker is unavailable.
- **CLI tests** -- REPL input/output, file interpretation, compilation output
- **Documentation examples** -- `DocExamplesTest` runs every runnable Lisp example under [`doc/`](doc/) on the interpreter and checks the shown output

## Contributing

rontolisp is written mostly by LLMs, so pull requests are not sought. For a request or a bug,
open an issue first. A pull request whose code was written with a top frontier model (such as
Claude Fable) will be looked at. Its commits are signed off (`git commit -s`) under the
Developer Certificate of Origin; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0

rontolisp uses no OpenJDK source code, and no code under a copyleft licence such as the GPL,
LGPL or EPL goes into rontolisp or into what it adds to a compiled program (libraries a program
loads itself come under their own licences: `doc/en/compiling/licenses.md`). The Java class
library a compiled Java program carries is rontolisp's own: each class is written from its
public specification (the Javadoc) and from the output of test programs run on a JDK, never
from a JDK's implementation. The code and data rontolisp takes from other projects are listed,
each with its licence text, in `THIRD-PARTY-NOTICES.txt`. Libraries kept whole as test fixtures
(`src/test/resources/`) keep their own licences, some of them copyleft (LLGPL), and ship in
neither. A contribution follows the same rule: nothing copied or translated from OpenJDK or
from copyleft-licensed code ([CONTRIBUTING.md](CONTRIBUTING.md)).

rontolisp is implemented with care under the provenance rules of
[`.kb/java-frontend-provenance.md`](.kb/java-frontend-provenance.md): what code may come from,
how a class of the Java library is written and checked, and how names are used.

## Trademarks

Java is a registered trademark of Oracle and/or its affiliates, and OpenJDK is a trademark of
Oracle America, Inc. Spring is a trademark of Broadcom Inc. and/or its subsidiaries. Apache
Maven is a trademark of The Apache Software Foundation. Cloudflare Workers is a trademark of
Cloudflare, Inc. Google App Engine is a trademark of Google LLC. macOS is a trademark of Apple
Inc. Envoy and wasmCloud are trademarks of The Linux Foundation. PostgreSQL is a registered
trademark of the PostgreSQL Community Association of Canada. Other names used in rontolisp and
its documentation, such as Clojure, WebAssembly and GraalVM, may be trademarks of their
respective owners. rontolisp is not affiliated with, endorsed by or sponsored by any of these
owners.
