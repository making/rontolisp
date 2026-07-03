# CLAUDE.md

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw clean spring-javaformat:apply package                    # Build executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test                             # Run all tests
```

User-facing behavior, limitations, and examples live in the documentation site
sources under `doc/en/**` (Markdown rendered to HTML by the standalone
`docs-tool/` generator; the runnable Lisp examples are verified by
`DocExamplesTest`). The README is a slim landing page that links to the site.
This file is the agent-facing companion: invariants you must not break and
pointers to where things live. When a constraint below ends with "(README)", the
user-facing description lives in `doc/` (and its rendered site) -- don't
duplicate it here. The docs nav/table of contents is `doc/en/nav.yaml`.

Implementation-level detail behind each constraint below (internal class names,
per-backend mechanics, edge cases, pinning tests) lives in `.kb/` -- see
`.kb/README.md` for the index. Read the one-line summary here first; open the
linked `.kb/*.md` file only when you need the full mechanics.

## Architecture Overview

Three execution modes share a common frontend (reader) and AST:

```
Source string
  -> LispReader (reader pkg) -> List<LispVal> (AST)
    -> [LispMacroExpander] -> expanded AST               # cond/and/or/setf -> if/let/progn/setq/rplaca/rplacd; defun is a special form
    -> LispEvaluator (eval pkg)                          # interpret
    -> JvmLispCompiler (codegen.jvm) -> byte[] (.class)  # compile to JVM
    -> WasmLispCompiler (codegen.wasm) -> byte[] (.wasm) # compile to WASM
```

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from [bfc](https://github.com/making/bfc).

Package dependency direction (no cycles allowed):

```
cli -> eval, codegen.*
codegen.jvm -> compiler, am.ik.jvm
codegen.wasm -> compiler, am.ik.wasm
compiler -> rontolisp (AST types only)
eval, reader -> rontolisp (AST types only)
```

## Key Design Constraints

- **Lisp-2 (separate function/variable namespaces) in all three backends**: a bare symbol is a variable reference only; a symbol in call position resolves in the function namespace only; a function value comes from `(function name)`/`#'name`/`symbol-function`. Details: `.kb/lisp2-namespaces.md`.
- **Lambda list extensions (`&optional`/`&rest`/`&key`/`&aux`/`&allow-other-keys`)**: `LambdaLists` desugars everything to the one native shape "required + `&rest`" (a `let*` prologue wrapped around the body); only `&rest` has per-backend support (variadic flag on the function records, surplus-arg packaging at call sites/dispatchers, negative arity in the eval registry). Not supported: `&whole`, `defmacro` lambda lists beyond `&rest`/`&body`, runtime-`eval` lambdas, `--no-gc`. Details: `.kb/lambda-lists.md`.
- **JVM Class Version 50 (Java 6)**: avoids mandatory StackMapTable (required from version 51+); the lenient version-50 verifier is relied on throughout.
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons struct goes inside a rec group.
- **symbolp/stringp**: quoted symbols and string literals share runtime representation, distinguished by a leading `"`.
- **consp in JVM**: both cons cells and function references use `Object[]`, distinguished by `arr[0] instanceof Integer`.
- **Three-pass compilation**: Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively compiles lambda bodies (top-level must compile before lambda iteration).
- **`do`/`return` and the `%block` non-local exit boundary**: `do` expands to a `let`/`while` loop; `return` is a non-local exit to the nearest enclosing `%block`, implemented differently per backend; only works where the surrounding operand stack is empty. Details: `.kb/do-return-block.md`.
- **`defmacro` (user macros) + read-time backquote**: backquote is expanded by the reader; `defmacro` is fully expanded at compile time by `UserMacroExpander` before the compilers run -- no backend codegen, no Jvm/Wasm macro compiler. Details: `.kb/defmacro-backquote.md`.
- **`gensym` + `macroexpand`/`macroexpand-1`**: implemented in all three backends; the interpreter and the compile-time macro expander use separate counters, so gensym names can diverge across backends. Details: `.kb/gensym-macroexpand.md`.
- **`defstruct`**: expanded by the shared `LispMacroExpander.expandDefstruct` into plain defuns over a tagged-list representation (no per-backend codegen); the compilers splice top-level forms before Pass 1 (top-level only on the compile path), and an accessor-position registry threaded into `expandSetf` makes accessors setf places. Options/`:include`/`#S` and `--no-gc` are unsupported. Details: `.kb/defstruct.md`.
- **`%` prefix convention**: internal helpers not part of the public API use a `%` prefix (e.g. `%remf-tail`).
- **Built-in function wrappers**: `BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so `#'+`/`#'car` etc. work as first-class values; this shape is internal encoding, not a real user function definition (Lisp-2).
- **JVM method name mangling**: `JvmLispCompiler.mangleMethodName()` maps `/`, `<`, `>`, `:` (forbidden in JVM method names) to `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`.
- **Packages**: a `cl`/`cl-user`/`rontolisp` namespace system resolved by a single `PackageResolver` pass before the evaluator/compilers run; CL colon semantics (`pkg:name` = external symbols only, `pkg::name` = any, canonical form matches externality so it re-resolves to itself). Details: `.kb/packages.md`.
- **`read`/`load`/`read-line`/file streams in all three backends**: a runtime reader/parser is emitted into compiled output, mirroring `eval`; a stream is an opaque, backend-local integer handle. Details: `.kb/read-load-streams.md`.
- **Compile-time `load` inlining (`LoadInliner`)**: a literal, top-level `(load "file.lisp")` is spliced in at compile time so JVM/WASM see the loaded defuns natively; paths resolve relative to the loading file. The same pass implements `require`/`provide` (idempotent module loading): real runtime functions on the interpreter, literal top-level directives on the compile path (nested/non-literal = compile error). Details: `.kb/load-inliner.md`.
- **`--dynamic` (late binding)**: opt-in; unresolvable calls/variables fall back to the embedded `eval` at those call sites instead of a compile error. Details: `.kb/dynamic-late-binding.md`.
- **`--component` (WASI 0.3 output)**: opt-in; the core module stays Preview-1-identical (fixed `FUNC_*` indices) and an adapter module implements WASI over async streams/futures. Details: `.kb/wasi-component.md`.
- **`rontolisp:wasm-export` + `--no-wasi` reactor mode**: exports a Lisp `defun` as host-callable WASM (Preview 1 only); `--no-wasi` emits a reactor module with no WASI imports. Details: `.kb/wasm-export-no-wasi.md`.
- **`rontolisp:wasm-import` (host functions) + export `:as` aliases**: declares a host function callable from Lisp like a defun (Preview 1 only; error under `--component`/`--no-gc`); compiled as a synthetic-defun wrapper calling a placeholder index that the `WasmImportInjector` post-pass resolves by prepending the imports and renumbering every function reference. Details: `.kb/wasm-import.md`.
- **`--optimize` (dead-code elimination)**: a post-pass tree-shaker for both WASM (`WasmTreeShaker`) and JVM (`JvmClassShaker`); skipped under `--component`. Details: `.kb/optimize-dead-code-elimination.md`.
- **`--no-gc` (non-GC scalar WASM lowering)**: a separate backend (`ScalarWasmCompiler`) using static type inference (`i64`/`f64`/string pointer) that emits a plain MVP module needing no `-W gc`. Details: `.kb/no-gc-scalar-wasm.md`.
- **Time/environment built-ins**: implemented in all three backends; WASM returns floats where magnitudes exceed the `i31` range. Details: `.kb/time-environment-builtins.md`.
- **`rontolisp:fetch` (outgoing HTTP)**: interpreter/JVM use `java.net.http.HttpClient`; WASM support is component-only via a WASI 0.2 http adapter. Details: `.kb/fetch-http.md`.
- **`rontolisp:tcp-*` (TCP sockets)**: `tcp-connect`/`tcp-listen`/`tcp-accept`/`tcp-local-port` return bidirectional handles in the file-stream handle space, so `read-line`/`write-line`/`read-byte`/`write-byte`/`close` work on sockets; interpreter/JVM use `java.net.Socket`, WASM is component-only over native WASI 0.3 `wasi:sockets` (fd >= 200 in the sockets adapter; run with `-S tcp=y -S inherit-network=y`; IPv4 literals only; fetch+tcp in one component is a compile error). Details: `.kb/tcp-sockets.md`.
- **`rontolisp:json-parse`/`json-stringify` (JSON)**: one hand-written Lisp-source library (`json.lisp`), lazily loaded by the interpreter and spliced into compiled programs by `JsonLibrary.process` (a cli/playground pre-pass like `UserMacroExpander` -- compiler unit tests must call it explicitly). WASM `equal`/`_hash` compare string content so runtime-built strings work as hash keys. Details: `.kb/json.md`.
- **`linalg` package (numpy-style vector/matrix ops) + standard array functions**: `linalg.lisp` is a second Lisp-source library (`LinalgLibrary`, json.lisp pattern but with no call-site rewriting; the interpreter lazy-loads on a `linalg:` function-lookup miss). `array-dimensions` and `row-major-aref` (+ `%row-major-aset`) are the backend primitives; `vector`/`svref`/`array-rank`/`array-dimension`/`array-total-size`/`array-row-major-index`/`coerce` are `LispMacroExpander` expansions (the JVM array-helper gate must list the derived names). Arrays are rank-n across all backends (JVM header slot 0 = an `Object[]` of Long dims). Details + a full API quick reference (signatures/semantics -- read it before writing any program that uses `linalg:`): `.kb/linalg.md`.
- **`java:` interop**: a built-in `java` package with reflection-based interop, supported in the interpreter and JVM compiler (via an embedded bridge class), not WASM. Details: `.kb/java-interop.md`.
- **Template-class embedding is a last resort**: prefer (1) macro expansion, then (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, only then (3) an embedded Java "template" class (used by `java:` interop). Details: `.kb/template-class-embedding.md`.
- **`eval` in all three backends**: a runtime tree-walking interpreter sharing the compiled value representation, emitted only when a program calls `eval`. Details: `.kb/eval-runtime.md`.
- **Hash tables**: interpreter/JVM use a real `HashMap`; WASM implements a true open-chaining hash table, not an alist. Details: `.kb/hash-tables.md`.

## Development Workflows

### Implementation Order

When adding a new built-in function or special form:

1. **Interpreter** (`LispEvaluator` / `Environment`) -> run `LispEvaluatorTest`
2. **JVM compiler** -> run `JvmLispCompilerTest`
3. **WASM compiler** -> run `WasmLispCompilerIntegrationTest`
4. Add a case to `src/test/resources/ci-spec.yaml` if it should be covered end-to-end (`CiSpecE2eTest`)
5. Update the docs (see "Updating the Documentation Site"). For a new built-in **function / macro / special form**: add a per-operator page (H1 = name, signature, short description, one runnable ```lisp example with a `; => value` annotation -- a static ```console block for forms needing stdin/files or that signal, e.g. `error`/`with-open-file`) under the matching `reference/{functions,macros,special-forms}/` directory and a `_catalog.yaml` entry, then run the `-Drontolisp.doc.fix=true` helper. The table page name links to it automatically.

### Adding a New Built-in Function

1. **LispNames.java / PackageRegistry.java**: Add the name constant, and add it to `PackageRegistry.CL_SYMBOLS` (otherwise it is misclassified as a user symbol).
2. **Environment.java**: Add in `createGlobal()` via `env.define("name", new LispFunction(...))`.
3. **JVM compiler**: Create `Jvm<Name>Compiler.java`, add a case in `JvmExprCompiler.compileCons()`.
4. **WASM compiler**: Create `Wasm<Name>Compiler.java`, add a case in `WasmExprCompiler.compileCons()`. Use `WasmEmitHelper.castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.
5. **BuiltinFunctionWrappers.java**: Add a `WRAPPER_DEFS` entry (arity + body AST) so it works as a first-class value.

### Adding a New Macro

Macros expand into existing primitives (`if`, `let`, `progn`, `rplaca`, `rplacd`) at the AST level. `LispMacroExpander` (in `am.ik.rontolisp`) is shared by the evaluator and both compilers. No per-compiler class is needed.

1. **LispMacroExpander.java**: Add `public static LispVal expand<Name>(LispCons cons)`. Add the name to `LispNames` and `PackageRegistry.CL_SYMBOLS`.
2. **LispEvaluator.java**: `evalCons()` case -> `return eval(LispMacroExpander.expand<Name>(cons), env);`
3. **JvmExprCompiler.java** / **WasmExprCompiler.java**: case -> `compileExpr(LispMacroExpander.expand<Name>(cons), ctx, ...)`.
4. **First-class value support** (if it should be passable to `map`/`reduce`/`funcall`): register as `LispFunction` in `Environment` (interpreter) AND add a `BuiltinFunctionWrappers` entry using the expanded body (compilers). Both are needed; omitting `Environment` causes `Undefined symbol` in interpreter/native-image mode.

### Adding a New Special Form

1. **LispEvaluator.java**: `evalCons()` case (special forms receive unevaluated arguments).
2. **JVM / WASM**: Create `Jvm/Wasm<Form>Compiler.java`, wire into `Jvm/WasmExprCompiler.compileCons()`.

### Updating the Documentation Site

The user-facing manual is Markdown under `doc/en/**`, rendered to a static HTML
site (`web/dist/docs`) by the standalone generator in `docs-tool/` and published
to GitHub Pages by `.github/workflows/pages.yaml`. Every doc change must be
mirrored across `doc/en/**` and `doc/ja/**` (and any future `doc/<lang>/`) in the
same commit -- same file set, same headings, byte-identical code fences; only
prose and titles are translated. Layout, code-fence conventions (`lisp`/`console`/
`bash`), and the full sync/build/preview procedure are in `.kb/documentation-site.md`.

After editing examples, normalize results and catch non-runnable examples:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

### Verifying Output Manually (all four backends)

A program is "verified" only when it has been run on **all four** backends:
interpreter, JVM, WASM Preview 1, and WASM component (`--component`). Don't stop
at three — the component path uses a different I/O adapter (and, for `random` /
time, a different entropy/clock source), so it can diverge from Preview 1.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
echo '(print (+ 1 2))' > test.lisp

# 1. Interpreter
java -jar $JAR test.lisp

# 2. JVM (class is named after the output file, so keep it path-free)
java -jar $JAR test.lisp -o Prog.class && java Prog

# 3. WASM Preview 1 (requires wasmtime 14+)
java -jar $JAR test.lisp -o test.wasm && wasmtime run -W gc test.wasm

# 4. WASM component / WASI 0.3 (requires wasmtime 46+)
java -jar $JAR test.lisp -o test-comp.wasm --component && \
  wasmtime run -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    test-comp.wasm
```

### Verifying the Native Image End-to-End (run locally before every push)

`./mvnw test` (JVM) **skips `CiSpecE2eTest`** because `-Drontolisp.binary` is
unset, so a stale `ci-spec.yaml` expectation passes the JVM run and only the CI
`native-image` job catches it. Reproduce that job locally before pushing —
always after editing `ci-spec.yaml`, and after any change that can shift
cross-backend output (a new built-in/macro/special form, anything touching
introspection, or a runtime behavior change). Requires GraalVM `native-image`
and `wasmtime` on `PATH` (build ~30s):

```bash
# 1. Build the native binary (same as the CI native-image job)
./mvnw -V --no-transfer-progress -Pnative clean package -DskipTests

# 2. Run the cross-backend E2E driver against the native binary
#    (interpreter / JVM / WASM in one run; names the exact failing case)
./mvnw -V --no-transfer-progress \
  -Dtest=CiSpecE2eTest -DfailIfNoTests=false \
  -Drontolisp.binary="$PWD/target/rontolisp" \
  test
```

A failure prints `[case '<name>' on <BACKEND>` with the offending lines; fix the
`ci-spec.yaml` `expected` (or the backend) and re-run step 2 only (the binary
does not need rebuilding unless Java sources changed).

## Development Requirements

- Java 25+
- No external dependencies for core libraries (reader, eval, codegen, am.ik.jvm, am.ik.wasm). The `docs-tool/` documentation generator is a separate Maven project (not part of the reactor) and may use flexmark/snakeyaml.
- Spring Java Format enforced via Maven plugin
- Use modern Java features (Records, Pattern Matching, Sealed Types, Text Blocks, etc.)
- Avoid circular references between classes and packages
- `src/test/resources/ci-spec.yaml` is the single source of truth for the cross-backend E2E test (`CiSpecE2eTest`, package `am.ik.rontolisp.e2e`). Each case is `name` + `source` + `expected` (with an optional `expectedByBackend` override). Cases share global state and run IN ORDER: the driver concatenates them into one program, runs the native binary once per backend (interpreter / JVM / WASM Preview 1 / WASM component via `--component`), and slices the output back per case. The driver only runs when `-Drontolisp.binary=<path>` is set, so `mvn test` on the JVM skips it.
- Documentation lives in `doc/en/**` (Markdown), built by `docs-tool/` and published by `.github/workflows/pages.yaml`; see "Updating the Documentation Site" above and `.kb/documentation-site.md`.
- WASM integration tests skipped if Docker unavailable

### After Task Completion

- Format: `./mvnw spring-javaformat:apply`
- Test: `./mvnw test`
- Web profile compile: `./mvnw -Pweb compile`. Required whenever `src/web/java`
  changed (e.g. the `Target_*` substitutions or `web/` `@JS` classes), or when a
  signature it overrides changed (e.g. `HttpSupport.request`). `src/web/java` is
  added to the sources only under the `web` profile, so the default `./mvnw test`
  does NOT compile it — a break there only surfaces in the web-playground CI job.
- Native E2E: build the native image and run `CiSpecE2eTest` against it (see
  "Verifying the Native Image End-to-End"). Required whenever `ci-spec.yaml` or
  any cross-backend output changed — `./mvnw test` does not cover it.
- Javadoc: `./mvnw javadoc:jar` - confirm 0 warnings/errors
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
