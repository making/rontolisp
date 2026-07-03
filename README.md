# rontolisp

A minimal Common Lisp subset implemented in Java. It supports three execution modes:

- **Interpreter** -- Tree-walking evaluation with REPL support
- **JVM compiler** -- Compiles Lisp to `.class` bytecode runnable on any JRE
- **WASM compiler** -- Compiles Lisp to `.wasm` binary using wasm-GC and WASI Preview 1

No external runtime dependencies for core libraries. The JVM and WASM bytecode generators are written from scratch without ASM or other code generation libraries. The CLI uses JLine for interactive REPL features (history, line editing).

## Documentation

**Full documentation: [https://making.github.io/rontolisp/docs/](https://making.github.io/rontolisp/docs/)**

The manual is organized into Getting Started, Compiling (JVM / WASM / WASI 0.3
components), a Language Reference (data types, special forms, macros, `format`,
built-in functions, packages, the function namespace), and Guides. Every
reference page is interactive: the Lisp examples run in your browser via the
same WebAssembly build that powers the playground -- press **Run** on any
example to evaluate it. The Markdown sources live under [`doc/`](doc/) and are
rendered to HTML by the standalone [`docs-tool/`](docs-tool/) generator.

**Try it in your browser: [https://making.github.io/rontolisp/playground.html](https://making.github.io/rontolisp/playground.html)** -- a playground where rontolisp itself runs as WebAssembly. Evaluate expressions in the REPL, and compile your source to downloadable `.class` and `.wasm` files, entirely client-side. A companion page, [compile & run](https://making.github.io/rontolisp/compile-run.html), compiles a set of Lisp definitions to a WASM module in the browser, then lets you call any function from that module with arguments you supply -- no download, no server. See [`web/README.md`](web/README.md) for how it is built and deployed.

## Requirements

- Java 25+ (for building and running the JAR)
- [GraalVM](https://www.graalvm.org/) 25+ (optional, for native image build)
- [wasmtime](https://wasmtime.dev/) (for running `.wasm` output, optional)

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

Build a native executable with GraalVM yourself (`target/rontolisp`, instant startup):

```bash
./mvnw -Pnative clean package
```

See [Build & Install](https://making.github.io/rontolisp/docs/en/getting-started/build.html) for details.

## Quick Start

The same source runs on all three backends:

```bash
echo '(print (+ 1 2))' > hello.lisp

rontolisp                                # REPL
rontolisp hello.lisp                     # interpret           -> 3
rontolisp hello.lisp -o Hello.class && java Hello              # JVM -> 3
rontolisp hello.lisp -o hello.wasm && wasmtime run -W gc hello.wasm  # WASM -> 3
```

For the REPL, file interpretation, JVM/WASM compilation (including
`rontolisp:wasm-export`, `rontolisp:wasm-import`, `--no-wasi`, `--optimize`,
`--component`, and `--dynamic`), the full language reference, and the
`rontolisp` extensions (`fetch`), see the
[documentation site](https://making.github.io/rontolisp/docs/).

## Project Structure

```
am.ik.rontolisp              -- Lisp data types (sealed interface)
am.ik.rontolisp.reader       -- Lexer + Parser
am.ik.rontolisp.eval         -- Tree-walking interpreter + Environment
am.ik.rontolisp.compiler     -- Shared compiler interface + FreeVarAnalyzer
am.ik.rontolisp.codegen.jvm  -- JVM .class generation
am.ik.rontolisp.codegen.wasm -- WASM .wasm generation (wasm-GC + WASI)
am.ik.rontolisp.cli          -- REPL + CLI entry point
am.ik.jvm                    -- JVM bytecode primitives
am.ik.wasm                   -- WASM binary primitives
```

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

## License

Apache License 2.0
