# CLAUDE.md

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw clean spring-javaformat:apply package                    # Build executable JAR (-exec classifier)
./mvnw spring-javaformat:apply test                             # Run all tests
```

Three layers of documentation, do not duplicate between them:

- `doc/en/**` + `doc/ja/**` (rendered by `docs-tool/`, examples verified by
  `DocExamplesTest`) -- user-facing behavior, limitations, examples. The README is
  a slim landing page; the nav is `doc/en/nav.yaml`.
- **This file** -- agent-facing: the architecture, the package-dependency rules,
  and the development workflows. It deliberately does NOT enumerate the language /
  backend invariants -- those live one-per-topic in `.kb/*.md`.
- `.kb/*.md` -- one file per topic: the invariant you must not break plus its full
  mechanics (internal class names, per-backend details, edge cases, pinning tests).
  Index with one-line summaries: `.kb/README.md`. **Before you change behavior in any
  area, grep `.kb/` for the topic and read the matching file** -- do not assume this
  file lists the constraint.

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

`am.ik.jvm` and `am.ik.wasm` are **language-independent** bytecode generation libraries ported from [bfc](https://github.com/making/bfc); `am.ik.wit` is their WIT-text sibling (lossless parser + wasm-tools-style printer, `.kb/wit.md`). None of the three may import rontolisp packages or external dependencies.

Package dependency direction (no cycles allowed):

```
cli -> eval, compiler, codegen.*, am.ik.wit
codegen.jvm -> compiler, am.ik.jvm
codegen.wasm -> compiler, am.ik.wasm, am.ik.wit
compiler -> rontolisp (AST types only), am.ik.wit
eval -> rontolisp (AST types only), compiler
reader -> rontolisp (AST types only)
```

`compiler` holds the backend-shared, backend-FREE directive front-ends: anything that must
behave identically on every backend (both `eval` and `cli` do) depends on it, never the
other way -- `compiler` depends on no backend.

**A compile-time AST pass that reads a file belongs in `eval`, not `cli`, and must read
through `SourceLoader`.** The browser playground (`RontoPlayground.frontend`, `src/web/java`)
never touches `cli` and has no filesystem, so a pass living in `cli` or calling `Files`
directly is simply absent there. Mechanics and history: `.kb/wit.md`.

## Key Design Constraints

Grouped pointers into `.kb/*.md` (full index: `.kb/README.md`) -- see the intro above for
the "grep before you change behavior" rule:

- Core value model, three-pass compilation, JVM method mangling -> `.kb/core-representation.md`
- Language semantics -- Lisp-2, lambda lists, `do`/`return`/`block`/`tagbody`/`go`, CLOS,
  `defstruct`, `defmacro`/backquote, dynamic (special) variables, multiple values, packages,
  reader features, conditions / `handler-case`, `eval`, the jzon-driven CL additions, ...
- Loading, libraries, I/O -- `load`/ASDF/`ql:quickload`, the Lisp-source libraries
  (`json`/`url`/`linalg`/`vec`/`usocket`/prelude), the dependency shim systems, `read`/streams,
  `fetch`/`http-handler`, TCP/TLS sockets, `java:` interop, time/environment.
- Backends & flags -- `--dynamic`, `--optimize`, `--component` (WASI 0.3), `--no-gc`, `--simd`,
  `wit-import`/`wit-export`/`wasm-export`, WASM GC strings.

Governing rule (why "all four backends" matters): where behavior must be identical across the
interpreter, the JVM, and both WASM backends, the topic's `.kb` file says so and names the
pinning test -- change the file and the test together, never one backend in isolation.

## Working principles

Distilled from post-mortems of features that landed cleanly vs. features that had to
be redone. Apply to any non-trivial change; the acceptance criterion is these plus
"all four backends" above.

- **A todo's "non-goals" are the author's scope, not an unconditional constraint.**
  If the essential fix requires touching a stated non-goal, propose the widened scope
  before routing around the real cause. A non-goal that the fix silently ignores keeps
  the root cause alive on the "lagging" side.

- **Prefer widening the shared normalizer over adding bypass wiring.** When a backend
  divergence is documented with a "reason for the divergence" clause and the reason
  now longer holds (e.g., the shared path just gained the wider semantics), retire
  the divergence in the SAME pass; leaving it in ships a hidden correctness gap on
  the lagging backend. A shared normalizer with a bigger contract is auditable;
  scattered "avoid the normalizer here" branches are not.

- **Turning a long-`@Disabled` test green is not just closing that one gate.** The
  test may have been skipped precisely because it exercises a code path with a latent
  bug on some backend; make the enablement a first-class finding in the same session,
  not a follow-up -- if you only satisfy the failing assertion and stop, the next
  enable will re-trap on the same latent bug.

- **When you touch a backend divergence, leave a re-evaluation trigger behind.** Write
  the reason for the divergence into the `.kb` file explicitly, so the next visitor
  can tell whether the reason still holds. A divergence with only a "how" and no "why"
  is a permanent gap.

## Development Workflows

### Implementation Order

When adding a new built-in function or special form:

1. **Interpreter** (`LispEvaluator` / `Environment`) -> run `LispEvaluatorTest`
2. **JVM compiler** -> run `JvmLispCompilerTest`
3. **WASM compiler** -> run `WasmLispCompilerIntegrationTest`
4. Add a case to `src/test/resources/ci-spec.yaml` if it should be covered end-to-end (`CiSpecE2eTest`)
5. Update the docs (see "Updating the Documentation Site"). For a new built-in **function / macro / special form**: add a per-operator page (H1 = name, signature, short description, one runnable ```lisp example with a `; => value` annotation -- a static ```console block for forms needing stdin/files or that signal, e.g. `error`/`with-open-file`) under the matching `reference/{functions,macros,special-forms}/` directory, a `_catalog.yaml` entry, and a row in the curated `reference/functions.md` table, then run the `-Drontolisp.doc.fix=true` helper.

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

Every doc change must be mirrored across `doc/en/**` and `doc/ja/**` (and any
future `doc/<lang>/`) in the same commit -- same file set, same headings,
byte-identical code fences; only prose and titles are translated. Layout,
code-fence conventions, and the build/preview procedure: `.kb/documentation-site.md`.

After editing examples, normalize results and catch non-runnable examples:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

### Verifying Output Manually (all four backends)

A program is "verified" only when it has been run on **all four** backends. Don't
stop at three -- the component path uses a different I/O adapter (and, for
`random` / time, a different entropy/clock source), so it can diverge from
Preview 1.

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
  wasmtime run -W gc=y test-comp.wasm
```

A program using `handler-case`/`ignore-errors`/`unwind-protect`/`catch`/`throw`
compiles in EH mode, an ASYNC component (async-defun/async-lambda/await, incl. every
fetch/serve program) forces EH mode too, and so does a **cross-lambda
`return-from`** (one inside a lambda that names an enclosing block, lowered to a
block-exit throw/catch): add `-W exceptions=y` to BOTH wasm run commands
(wasmtime 37+; without it the module fails to parse). A fetch component
additionally needs `-S http=y`. Programs without those forms are byte-identical
to pre-EH output and keep the flags above.

### Verifying the Native Image End-to-End (run locally before every push)

`./mvnw test` (JVM) **skips `CiSpecE2eTest`** because `-Drontolisp.binary` is
unset, so a stale `ci-spec.yaml` expectation passes the JVM run and only the CI
`native-image` job catches it. Reproduce that job locally before pushing --
always after editing `ci-spec.yaml`, and after any change that can shift
cross-backend output. Requires GraalVM `native-image` and `wasmtime` on `PATH`:

```bash
# 1. Build the native binary (same as the CI native-image job)
./mvnw -V --no-transfer-progress -Pnative clean package -DskipTests

# 2. Run the cross-backend E2E driver against the native binary
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
- No external dependencies for core libraries (reader, eval, codegen, am.ik.jvm, am.ik.wasm, am.ik.wit). The `docs-tool/` generator is a separate Maven project (not in the reactor) and may use flexmark/snakeyaml.
- Spring Java Format enforced via Maven plugin
- Use modern Java features (Records, Pattern Matching, Sealed Types, Text Blocks, etc.)
- Avoid circular references between classes and packages
- `src/test/resources/ci-spec.yaml` is the single source of truth for the cross-backend E2E test (`CiSpecE2eTest`). Cases share global state and run IN ORDER: the driver concatenates them into one program, runs the native binary once per backend, and slices the output back per case. It only runs when `-Drontolisp.binary=<path>` is set.
- WASM integration tests skipped if Docker unavailable. They run `wasmtime` from a
  prebuilt image (`WasmtimeSupport.IMAGE`, a pinned wasmtime on Debian, tracked as
  `:latest` and always re-pulled) that `.github/workflows/wasmtime-image.yaml` builds from
  `.github/docker/wasmtime/Dockerfile` and pushes to GHCR. Bump the wasmtime version in the
  Dockerfile ARG and the workflow `WASMTIME_VERSION` together, then re-run the workflow --
  no test change needed. Keep it >= 46 for the `--component` tests.

### After Task Completion

- Format: `./mvnw spring-javaformat:apply`
- Test: `./mvnw test`
- Web profile compile: `./mvnw -Pweb compile`. Required whenever `src/web/java` changed (the `Target_*` substitutions, `web/` `@JS` classes) or a signature it overrides changed. `src/web/java` compiles only under the `web` profile, so `./mvnw test` does NOT catch a break there -- only the web-playground CI job does. It leaves `target/classes` holding the WEB source set, so run it AFTER the test suite, or `clean` in between: a later `./mvnw test` without `clean` reuses that tree and fails with `NoClassDefFoundError` on classes the web profile excludes (e.g. `codegen.wasm`), which reads like a real regression and is not one.
- Native E2E: build the native image and run `CiSpecE2eTest` against it (see above). Required whenever `ci-spec.yaml` or any cross-backend output changed.
- Javadoc: `./mvnw javadoc:jar` - confirm 0 warnings/errors (except for errors about `Version` class)
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
