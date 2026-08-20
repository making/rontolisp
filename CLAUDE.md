# CLAUDE.md

```bash
./mvnw clean spring-javaformat:apply compile   # compile
./mvnw clean spring-javaformat:apply package   # executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test            # all tests
```

Three documentation layers, no duplication between them:

- `doc/en/**` + `doc/ja/**` -- user-facing behavior and examples (rendered by `docs-tool/`,
  verified by `DocExamplesTest`).
- **This file** -- architecture, package rules, workflows.
- `.kb/*.md` -- one file per topic: the invariant plus its full mechanics. Index:
  `.kb/README.md`. **Before changing behavior in any area, grep `.kb/` for the topic and
  read the matching file** -- this file does not list the constraints.

## Architecture

```
Source string
  -> LispReader (reader pkg) -> List<LispVal> (AST)
    -> [LispMacroExpander] -> expanded AST               # cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm)
```

`am.ik.jvm`, `am.ik.wasm` and `am.ik.wit` are **language-independent** libraries; none may
import rontolisp packages or external dependencies.

Package dependency direction (no cycles allowed):

```
cli -> eval, compiler, codegen.*, macro, reader, format, am.ik.wit
codegen.jvm -> compiler, macro, am.ik.jvm
codegen.wasm -> compiler, macro, am.ik.wasm, am.ik.wit
compiler -> macro, rontolisp (AST types only), am.ik.wit
eval -> macro, compiler, reader, rontolisp (AST types only)
macro -> reader, rontolisp (AST types only)
reader -> rontolisp (AST types only)
format -> (nothing)
```

- `format` depends on nothing, not even `reader`: it needs the source verbatim and has its
  own lossless CST front end (`.kb/formatter.md`).
- `compiler` holds backend-shared, backend-FREE front-ends and depends on no backend.
- `macro` sits ABOVE `reader` so an expander may build injected AST by reading Lisp source;
  therefore the root `rontolisp` package must never import `macro`.
- **A compile-time AST pass that reads a file belongs in `eval`, not `cli`, and must read
  through `SourceLoader`** -- the browser playground (`src/web/java`) never touches `cli`
  and has no filesystem (`.kb/wit.md`).

Where behavior must be identical across the interpreter, the JVM and both WASM backends,
the topic's `.kb` file says so and names the pinning test -- change the file and the test
together, never one backend in isolation.

## Development Workflows

### Adding a Built-in Function

1. `LispNames` constant + `PackageRegistry.CL_SYMBOLS` entry (else it is misclassified as a
   user symbol).
2. `Environment.createGlobal()`: `env.define("name", new LispFunction(...))` -> `LispEvaluatorTest`
3. `Jvm<Name>Compiler` + a case in `JvmExprCompiler.compileCons()` -> `JvmLispCompilerTest`
4. `Wasm<Name>Compiler` + a case in `WasmExprCompiler.compileCons()` -> `WasmLispCompilerIntegrationTest`
   (`WasmEmitHelper.castI31GetS()` to unbox, `ref.i31` to re-box)
5. `BuiltinFunctionWrappers.WRAPPER_DEFS` entry so it works as a first-class value.
6. A case in `src/test/resources/ci-spec.yaml` if it deserves end-to-end coverage.
7. Docs: a per-operator page under `reference/{functions,macros,special-forms}/` (H1 = name,
   signature, one runnable ```lisp example with a `; => value`), a `_catalog.yaml` entry, and
   a row in `reference/functions.md`.
8. If its trailing arguments are a BODY, an `am.ik.rontolisp.format.IndentRules` entry --
   without one `rontolisp format` lays the body out as a function call (`.kb/formatter.md`).

### Adding a Macro

Macros expand into existing primitives at the AST level; `LispMacroExpander` is shared by the
evaluator and both compilers, so no per-compiler class is needed.

1. `LispMacroExpander.expand<Name>(LispCons)`, plus `LispNames` / `PackageRegistry.CL_SYMBOLS`.
2. `LispEvaluator.evalCons()` case -> `eval(LispMacroExpander.expand<Name>(cons), env)`.
3. `Jvm`/`WasmExprCompiler` case -> `compileExpr(LispMacroExpander.expand<Name>(cons), ...)`.
4. To pass it to `map`/`reduce`/`funcall`: register as a `LispFunction` in `Environment` AND
   add a `BuiltinFunctionWrappers` entry. Both -- omitting `Environment` causes
   `Undefined symbol` in interpreter / native-image mode.

### Adding a Special Form

`LispEvaluator.evalCons()` case (arguments arrive unevaluated), plus
`Jvm/Wasm<Form>Compiler` wired into `Jvm/WasmExprCompiler.compileCons()`.

### Documentation Site

Every doc change is mirrored across `doc/en/**` and `doc/ja/**` in the same commit -- same
file set, same headings, byte-identical code fences; only prose and titles are translated.
Layout and preview: `.kb/documentation-site.md`. `docs-tool/` is not in the root reactor, so
run `./mvnw -f docs-tool/pom.xml test` after touching `doc/` layout.

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixShownResults test   # rewrite shown results
./mvnw -Dtest=DocExamplesTest test                                            # verify
```

### Verifying Output Manually (all four backends)

A program is "verified" only when it has run on **all four**. The component path uses a
different I/O adapter (and entropy/clock source), so it can diverge from Preview 1.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
echo '(print (+ 1 2))' > test.lisp

java -jar $JAR test.lisp                                                    # interpreter
java -jar $JAR test.lisp -o Prog.class && java Prog                         # JVM (keep the name path-free)
java -jar $JAR test.lisp -o test.wasm && wasmtime run -W gc test.wasm       # WASM preview 1
java -jar $JAR test.lisp -o test-comp.wasm --component && \
  wasmtime run -W gc=y test-comp.wasm                                       # WASM component (WASI 0.3)
```

`handler-case`/`ignore-errors`/`unwind-protect`/`catch`/`throw`, an async component
(incl. every fetch/serve program), and a cross-lambda `return-from`/`go` all compile in EH
mode: add `-W exceptions=y` to both wasm runs or the module fails to parse. A fetch
component also needs `-S http=y`.

### Native Image E2E (run locally before every push)

`./mvnw test` **skips `CiSpecE2eTest`** (`-Drontolisp.binary` unset), so a stale
`ci-spec.yaml` expectation only fails in CI. Reproduce it after editing `ci-spec.yaml` or
changing anything that can shift cross-backend output:

```bash
./mvnw -Pnative clean package -DskipTests
./mvnw -Dtest=CiSpecE2eTest -DfailIfNoTests=false -Drontolisp.binary="$PWD/target/rontolisp" test
```

A failure prints `[case '<name>' on <BACKEND>`; re-run step 2 only unless Java sources changed.

### Examples Suite

`ExamplesE2eTest` runs every example in `examples/examples.yaml` on every backend it
declares. `./mvnw test` skips it, so run it after touching an example or a surface they
exercise:

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
# narrow it while iterating: -Drontolisp.examples.only=cloudflare
```

## Requirements

- Java 25+
- No external dependencies in the core libraries (reader, eval, codegen, `am.ik.*`).
  `docs-tool/` is a separate Maven project and may use flexmark/snakeyaml.
- Spring Java Format enforced by the Maven plugin; modern Java (records, pattern matching,
  sealed types, text blocks); no circular references between classes or packages.
- `src/test/resources/ci-spec.yaml` is the single source of truth for `CiSpecE2eTest`. Cases
  share global state and run IN ORDER: the driver concatenates them into one program, runs
  the binary once per backend, and slices the output back per case.
- WASM integration tests are skipped without Docker. They run `wasmtime` from
  `WasmtimeSupport.IMAGE`, built by `.github/workflows/wasmtime-image.yaml`; bump the
  Dockerfile ARG and the workflow `WASMTIME_VERSION` together. Keep it >= 47 --
  47+ inlines final-type casts, without which serve throughput collapses under concurrency
  (`.kb/wasm-gc-final-types.md`).

## After Task Completion

- Format Java: `./mvnw spring-javaformat:apply`, plus
  `./mvnw -f docs-tool/pom.xml spring-javaformat:apply` if `docs-tool/` changed. Wrap a
  block the formatter mangles in `// @formatter:off` / `// @formatter:on` rather than
  skipping it.
- Format Lisp: `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar format examples/ src/main/resources/ size-report/programs/`
- Test: `./mvnw test`
- Web profile: `./mvnw -Pweb compile` whenever `src/web/java` or a signature it overrides
  changed -- `./mvnw test` does not compile it. Run it AFTER the test suite (or `clean` in
  between): it leaves the web source set in `target/classes`, and a later `./mvnw test`
  without `clean` then fails with `NoClassDefFoundError` on excluded classes, which looks
  like a regression and is not one.
- Native E2E (above) whenever `ci-spec.yaml` or cross-backend output changed.
- Javadoc: `./mvnw clean javadoc:jar` -- must stay at 0 warnings. The goal does not fork
  `generate-sources`, so nothing in `src/main/java` may depend on a generated source.
- Notify: `osascript -e 'display notification "<Body>" with title "<Title>"'`
