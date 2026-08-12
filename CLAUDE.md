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
cli -> eval, compiler, codegen.*, macro, reader, format, am.ik.wit
codegen.jvm -> compiler, macro, am.ik.jvm
codegen.wasm -> compiler, macro, am.ik.wasm, am.ik.wit
compiler -> macro, rontolisp (AST types only), am.ik.wit
eval -> macro, compiler, reader, rontolisp (AST types only)
macro -> reader, rontolisp (AST types only)
reader -> rontolisp (AST types only)
format -> (nothing)
```

`format` (the `rontolisp format` source formatter) depends on NOTHING -- not even
`reader`. That is the point: it needs the source verbatim, and the reader upcases symbols,
evaluates `#+`/`#-`, rewrites `'x` and drops comments, so it has its own lossless CST
front end. `.kb/formatter.md`.

`compiler` holds the backend-shared, backend-FREE directive front-ends: anything that must
behave identically on every backend (both `eval` and `cli` do) depends on it, never the
other way -- `compiler` depends on no backend.

`macro` (`LispMacroExpander`, `LispAsync`, `SpecialVarCollector`) sits ABOVE `reader`, and
that direction is the point: an expander pass may build the AST it injects by READING Lisp
source (`LispReader`) instead of hand-assembling `LispCons` nodes in Java. `rontolisp`
(the AST types plus the name/package/registry tables) must therefore never reference
`macro` -- a root-package helper that wants an expander predicate gets the predicate moved
down to it (`LispNames.isCarCdrComposition`, `LambdaLists.setfFunctionPlaceName`), never an
import back up.

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
- Frontend source positions -- `file:line:column` in reader AND post-read errors, the
  cons-identity rule an AST pass must honour to keep them, and the position-inheriting
  rebuild a REWRITING pass owes them -> `.kb/source-positions.md`
- Compile-time evaluation of a pure built-in over literal arguments (always on, both
  compile paths): the curated table, what is deliberately excluded and why, and the
  four-backend differential harness that decides what may be in it ->
  `.kb/pure-builtin-fold.md`
- A top-level form is a STATEMENT: both compile paths drop its value, so nothing may be
  emitted only to be dropped -- the constant a resolver leaves behind (`in-package` -> a
  quoted symbol) is deleted, and `defvar`/`defparameter`/`defconstant` are compiled for
  effect instead of building the name they return (always on, like the fold above) ->
  `.kb/toplevel-statement-values.md`
- `(boundp 'name)` over a LITERAL symbol is decided at compile time (always on, both
  compile paths, and in the CLI/playground BEFORE the tree-shaker, because the portable
  `(unless (boundp '+k+) (defconstant +k+ v))` guard hides the definition it wraps): what
  the top-level order makes decidable, every position it does not, and the free soundness
  gate -> `.kb/compile-time-boundp.md`
- Backends & flags -- `--dynamic`, `--optimize`, `--component` (WASI 0.3), `--no-gc`, `--simd`,
  `wit-import`/`wit-export`/`wasm-export`, WASM GC strings.
- `rontolisp format`, the source formatter: the whitespace-ONLY invariant (identical token
  stream + fixpoint, pinned over every checked-in `.lisp`/`.asd`), why it reads source with
  its own lossless CST reader rather than `LispReader`, and the deliberate divergences from
  trivial-formatter -> `.kb/formatter.md`

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
6. A new operator whose trailing arguments are a BODY (not an argument list) also needs an
   `am.ik.rontolisp.format.IndentRules` entry -- without one `rontolisp format` lays it out
   as a function call and aligns its whole body under its first argument. A plain function
   needs nothing. `.kb/formatter.md`

### Adding a New Built-in Function

1. **LispNames.java / PackageRegistry.java**: Add the name constant, and add it to `PackageRegistry.CL_SYMBOLS` (otherwise it is misclassified as a user symbol).
2. **Environment.java**: Add in `createGlobal()` via `env.define("name", new LispFunction(...))`.
3. **JVM compiler**: Create `Jvm<Name>Compiler.java`, add a case in `JvmExprCompiler.compileCons()`.
4. **WASM compiler**: Create `Wasm<Name>Compiler.java`, add a case in `WasmExprCompiler.compileCons()`. Use `WasmEmitHelper.castI31GetS()` to unbox to `i32` and `ref.i31` to re-box.
5. **BuiltinFunctionWrappers.java**: Add a `WRAPPER_DEFS` entry (arity + body AST) so it works as a first-class value.

### Adding a New Macro

Macros expand into existing primitives (`if`, `let`, `progn`, `rplaca`, `rplacd`) at the AST level. `LispMacroExpander` (in `am.ik.rontolisp.macro`) is shared by the evaluator and both compilers. No per-compiler class is needed.

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

The same tree is also published as an **agent skill** (`docs-tool` `skill` mode ->
`/skill/` on the Pages site), generated on every deploy. It is a VIEW of `doc/`, so
a language rule is written on a doc page and inlined -- never restated in the skill
template. Its test regenerates the bundle and fails on a link a doc rename broke,
and `docs-tool` is not in the root reactor, so run
`./mvnw -f docs-tool/pom.xml test` after touching `doc/` layout.
`.kb/documentation-site.md`.

After editing examples, normalize results and catch non-runnable examples:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

### Artifact Sizes Belong in `size-report/`, Not in a README

No `examples/**/README.md` may quote a byte count of a compiled artifact. Those
numbers went stale every time the compiler changed, in a dozen places at once.
`size-report/measure.sh` measures every tracked artifact -- `size-report/programs/`
across the flag matrix, and the Cloudflare Worker modules raw and
gzipped -- writes `size-report/results/`, and
`.github/workflows/size-report.yaml` re-runs it daily and commits the diff only
when a measured number actually moved (a rerun that changes nothing but the
date/commit stamps is restored, not committed). An
example README that wants to talk about size links to
`size-report/results/*.md`. A new artifact worth tracking gets a row in
`measure.sh`'s `wasm_builds` / `worker_builds` table, not a paragraph in a
README.

Each `results/*.md` carries its own prose -- what that family measures, how to
read the numbers -- below the generated table, so the explanation travels with
the numbers instead of sitting one directory up. That prose is
`size-report/notes/<report>.md`, appended verbatim by `measure.sh`. Edit
`notes/`; anything written into `results/` by hand is gone at the next run.
`size-report/README.md` stays the build-and-run page.

### ANSI Conformance Belongs in `ansi-test/`

`ansi-test/measure.sh` runs the ANSI Common Lisp test suite against the
INTERPRETER and rewrites `ansi-test/results/interpreter.md` -- the per-chapter
pass rate plus the ranked list of what the failures blame. The suite is not
vendored: `ansi-test/fetch.sh` clones it at a pinned revision into the
git-ignored `ansi-test/suite/`, and every report names the revision it measured.

**Do not run the whole suite in a session.** `.github/workflows/ansi-report.yaml`
owns the checked-in report: it re-measures daily on one machine and commits the
result `[skip ci]` only when a count actually moved. A local whole-suite run
answers a different question -- a different machine, a different stall window --
and the point of the report is the MOVEMENT, which only reads if the measurement
does not drift with who took it. What a session runs is one chapter
(`ansi-test/measure.sh cons`), which writes `results/partial.md` and leaves the
baseline alone. After a change expected to move the numbers, dispatch the
workflow rather than committing a locally measured `interpreter.md`.

The driver (`src/test/java/am/ik/rontolisp/ansi/`) runs one child JVM per
chapter and evaluates ONE TOP-LEVEL FORM AT A TIME under `catch (Throwable)`, so
a form we cannot take costs that form and not the 700 tests behind it. Read
`ansi-test/README.md` before changing it -- what the numbers mean depends on that
choice and on the `rt.lsp` stand-in (`ansi-test/rt-shim.lisp`).

A failing ANSI test is not automatically a bug worth fixing: the suite measures
full ANSI CL, which this implementation does not set out to be. What the report
is for is deciding WHICH gap to close next, and noticing when a change moves the
number.

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
`return-from` or `go`** (one inside a lambda naming an enclosing block, or
targeting an enclosing `tagbody`'s tag -- lowered to a block-exit throw/catch):
add `-W exceptions=y` to BOTH wasm run commands
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
  no test change needed. Keep it >= 47: 46 still runs the `--component` tests, but only
  47+ inlines final-type casts, without which serve throughput collapses under
  concurrency (`.kb/wasm-gc-final-types.md`).

### After Task Completion

- Format (Java): `./mvnw spring-javaformat:apply`
- Format (Lisp): with a built exec jar, format the checked-in sources in place before the
  test run:
  ```bash
  java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar format examples/ src/main/resources/ size-report/programs/
  ```
- Test: `./mvnw test`
- Web profile compile: `./mvnw -Pweb compile`. Required whenever `src/web/java` changed (the `Target_*` substitutions, `web/` `@JS` classes) or a signature it overrides changed. `src/web/java` compiles only under the `web` profile, so `./mvnw test` does NOT catch a break there -- only the web-playground CI job does. It leaves `target/classes` holding the WEB source set, so run it AFTER the test suite, or `clean` in between: a later `./mvnw test` without `clean` reuses that tree and fails with `NoClassDefFoundError` on classes the web profile excludes (e.g. `codegen.wasm`), which reads like a real regression and is not one.
- Native E2E: build the native image and run `CiSpecE2eTest` against it (see above). Required whenever `ci-spec.yaml` or any cross-backend output changed.
- Javadoc: `./mvnw clean javadoc:jar` - confirm 0 warnings/errors. It must stay at zero:
  the goal does NOT fork `generate-sources`, so nothing in `src/main/java` may depend on a
  generated source (that is why `Version` is checked in and reads a generated
  `version.properties` resource instead of being generated itself).
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
