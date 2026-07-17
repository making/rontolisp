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
- **This file** -- agent-facing: the invariants you must not break, one line each,
  plus the workflows.
- `.kb/*.md` -- the full mechanics behind each invariant (internal class names,
  per-backend details, edge cases, pinning tests). Index: `.kb/README.md`. Read the
  line here first; open the `.kb` file only when you need the "why exactly".

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

`compiler` holds the backend-shared, backend-FREE directive front-ends, so anything that
must behave identically on every backend may depend on it: `eval` does (`WitExportInliner`
and `LispEvaluator.evalWitExport` both run the `wit-export` contract check through
`WitExportDirective`) and so does `cli` (`WitScaffolder` -> `am.ik.wit` directly). The
direction stays one-way -- `compiler` imports neither, and depends on no backend.

**A compile-time AST pass that reads a file belongs in `eval`, not `cli`, and must read
through `SourceLoader`** (`WitExportInliner` is the model): the browser playground has its
own front-end (`RontoPlayground.frontend`, `src/web/java`) which never touches `cli` and
has no filesystem, so a pass living in `cli` or calling `Files` directly is simply absent
there -- which is how `wit-export` first shipped, working in the playground's REPL but
dying with `Cannot compile` on its Compile buttons.

## Key Design Constraints

### Core representation (no `.kb` file -- the detail is here)

- **JVM Class Version 50 (Java 6)**: avoids the mandatory StackMapTable of v51+; the lenient v50 verifier is relied on throughout.
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports; only the cons struct goes inside a rec group.
- **symbolp/stringp**: quoted symbols and string literals share a runtime representation, distinguished by a leading `"`.
- **consp in JVM**: cons cells and function references are both `Object[]`, distinguished by `arr[0] instanceof Integer`.
- **Three-pass compilation**: Pass 1 collects defuns; 2a compiles defun bodies, 2b top-level, 2c iteratively compiles lambda bodies (top-level must compile before lambda iteration).
- **`%` prefix convention**: internal helpers outside the public API are `%`-prefixed (e.g. `%remf-tail`).
- **Built-in function wrappers**: `BuiltinFunctionWrappers` synthesizes `(setq name (lambda ...))` defuns so `#'+`/`#'car` work as first-class values -- internal encoding, not a real user definition (Lisp-2).
- **JVM method name mangling**: `JvmLispCompiler.mangleMethodName()` maps `/ < > : .` to `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`/`$dot`, plus `%` -> `$pct`. `%` is legal in a JVM method name, but OpenJDK's JVMCI uses the method name as a *format string*, so a hot `%`-prefixed defun aborts its JIT compilation and prints a warning into the program's stdout (`.todo/120`).
- **Template-class embedding is a last resort**: prefer (1) macro expansion, (2) a hand-assembled `Jvm/Wasm<Name>RuntimeBuilder`, only then (3) an embedded Java template class (used by `java:` interop). `.kb/template-class-embedding.md`.

### Language semantics

- **Lisp-2**: a bare symbol is a variable only; call position resolves in the function namespace only; function values come from `#'name`/`symbol-function`. `.kb/lisp2-namespaces.md`
- **Lambda lists** (`&optional`/`&rest`/`&key`/`&aux`/`&allow-other-keys`): `LambdaLists` desugars everything to the one native shape "required + `&rest`". No `&whole`/`&environment`, no runtime-`eval` lambdas, no `--no-gc`. `.kb/lambda-lists.md`
- **`do`/`return`/`%block`**: `return` is a non-local exit to the nearest enclosing `%block`, and works mid-expression on every backend (the JVM discards the abandoned operands). `return-from` is lite -- rewritten to `return`, scoped to the **nearest enclosing function** (a deviation from CL). `.kb/do-return-block.md`
- **`defmacro` + read-time backquote + `destructuring-bind`**: backquote is expanded by the reader; `defmacro` is fully expanded at compile time by `UserMacroExpander` -- no backend codegen. `.kb/defmacro-backquote.md`, `.kb/packages.md`
- **`gensym`/`macroexpand`**: all three backends; the interpreter and the compile-time expander use separate counters, so gensym names can differ across backends. `.kb/gensym-macroexpand.md`
- **Runtime symbol API**: symbols compare by name (no intern table); `symbol-name` is verbatim/case-preserving; `boundp`/`symbol-value` see GLOBAL variables only. `.kb/symbol-runtime-api.md`
- **`defstruct`**: a shared `LispMacroExpander` expansion into plain defuns over a tagged list; top-level only on the compile path. No options/`:include`/`#S`, no `--no-gc`. `.kb/defstruct.md`
- **CLOS static subset** (`defclass`/`defgeneric`/`defmethod`/`make-instance`/`slot-value`): the defstruct pattern extended -- a `ClosRegistry` + ONE generated dispatcher defun per generic (single dispatch on arg 1). Single inheritance; no qualifiers/`call-next-method`, no MOP, no `--no-gc`. `.kb/clos.md`, `.todo/40`
- **`flet`/`labels`**: expansions to let-bound lambdas plus a Lisp-2 body rewrite. `macrolet`/`symbol-macrolet` unsupported. `.kb/flet-labels.md`
- **Dynamic (special) variables**: `defvar`/`declaim special` + `let` = a **shallow binding** (save/set/restore over the global cell), thread-scoped on the interpreter. Compile-path lite limits: `progv` = compile error, unwinding via `return` does not restore, `--no-gc` rejects specials. `.kb/dynamic-special-variables.md`
- **async/await** (`rontolisp:async-defun`/`async-lambda`/`await` + futures/streams; `rontolisp:async` is a pure-frontend WRAPPER macro -- `(async (defun ...))`/`(async (lambda ...))` -- rewritten before every scanner/backend sees the program; the promise-era `then`/`promisep` are DELETED): eager-start (body runs to the first unsettled await before the caller resumes); `await` is a SPECIAL FORM legal only inside async bodies and at top level (`LispAsync` checks the resolved AST on every backend); an errored future re-signals AT AWAIT; interpreter/JVM = virtual threads (real parallelism after the first suspension; `eval/AsyncRuntime` is the ONLY evaluator-reachable thread site -- the playground substitutes it), `--component` = entry+resume state machines over first-class futures, driven by a blocking event loop at synchronous boundaries and by the REAL serve callback at the `handle` boundary (a pending handler returns WAIT to the host; per-task waitable-sets + context slot + doorbell streams let two requests interleave in one instance; cooperative single-threaded; forces EH mode, so async components need `wasmtime -W exceptions=y`), P1 = degenerate synchronous, `--no-gc` = compile error. Streams: interpreter/JVM everywhere; on `--component` only the fetch/serve body streams exist (guest `make-stream`/`stream-write` = compile error). fetch/serve `:body` is a STREAM on every backend. `rontolisp:read-all` is a prelude async-defun. `.kb/async-await.md`
- **Multiple values (syntactic tier)**: `values`/`multiple-value-bind`/... are lowerings with NO runtime representation -- the consumer must recognize the producer syntactically (a literal `(values ...)`, the floor-family, `gethash`); any other producer yields its primary value only. `.kb/multiple-values.md`
- **Error handling** (`unwind-protect`, condition objects, `handler-case`): conditions are CLOS-subset instances; interpreter + JVM + wasm-GC catch by type (**only `--no-gc` rejects `unwind-protect`/`handler-case`/`ignore-errors` at compile time**). The wasm-GC path is EH-MODE GATED: only a program containing a catching form gets the `$lisp-cond` tag/`try_table` machinery and needs `wasmtime -W exceptions=y` (37+); anything else stays byte-identical. wasm-GC catches signaled conditions only — runtime traps stay uncatchable there. No restarts. On the JVM, a catching form in an ARGUMENT position spills the live operand stack around its protected region (entering a handler discards it), which is what `am.ik.jvm.OperandStack` -- a typed model of the stack that `Ctx.emit` feeds, and the source of a real `max_stack` -- exists for. `.kb/error-handling.md`
- **Declarations / `eval-when` / `check-type`**: `declare`/`declaim`/`the` are no-ops, `eval-when` -> `progn` (+ top-level flattening so nested defuns are collected); `check-type`/`assert` are lite error-signaling expansions. `.kb/declarations-type-checks.md`
- **Packages**: `cl`/`cl-user`/`rontolisp` resolved by one `PackageResolver` pass with CL colon semantics; nicknames + `:import-from`; `:shadow` rejected. `.kb/packages.md`
- **Reader features** (`#+`/`#-`, `*features*`, `#|...|#`, `#.`): resolved entirely in the frontend against `reader.Features`, so a compiled program's feature set is fixed at compile time; the emitted runtime reader knows none of it. `.kb/reader-features.md`
- **Macro-time pure-config-setter replay**: a top-level `(setf (PLACE ...) V)` is re-evaluated into the macro-time evaluator, but ONLY when the writer is statically judged a pure config setter (deny-by-default allow-list). A workaround for the lack of special bindings at expansion time. `.kb/asdf.md` (cl-who)
- **Hash tables**: interpreter/JVM use a real `HashMap`; WASM implements a true open-chaining table, not an alist. `.kb/hash-tables.md`
- **`eval` in all three backends**: a runtime tree-walking interpreter over the compiled value representation, emitted only when the program calls `eval`. `.kb/eval-runtime.md`

### Loading, libraries, I/O

- **`load` inlining (`LoadInliner`)**: a literal top-level `(load "f.lisp")` is spliced at compile time; also implements `require`/`provide`. `.kb/load-inliner.md`
- **ASDF subset** (`asdf:defsystem`/`load-system`, `ql:quickload`): an API-compatible mini-ASDF, NOT a port -- `.asd` files are parsed as plain data and spliced inside the `LoadInliner` recursion. Unsupported clauses are hard errors. `.kb/asdf.md`, `.todo/54`
- **Library defun pruning (default-on) + constant-pool dedup**: `LibraryDefunPruner` drops spliced linalg/vec/json/url/prelude defuns unreachable from the user program (`--no-prune`/`--dynamic` disable it), so runtime-forged names of pruned functions error. `.kb/library-defun-pruning.md`
- **Lisp-source libraries** (the go-to pattern for anything expressible in core primitives): `json.lisp`, `url.lisp`, `linalg.lisp`, `vec.lisp`, `usocket.lisp`, and the `LispPreludeLibrary` prelude (`equalp`/`string<`) -- interpreter lazy-loads on first resolution, the cli/playground pre-pass splices into compiled programs. `.kb/json.md`, `.kb/url.md`, `.kb/linalg.md`, `.kb/vec.md`, `.kb/tcp-sockets.md`, `.kb/asdf.md`
- **`linalg` package + standard array functions**: `array-dimensions`/`row-major-aref` are the backend primitives, the rest are macro expansions; arrays are rank-n on all backends. Read `.kb/linalg.md` (it has the full API reference) before writing any `linalg:` program.
- **Fill-pointer / `:adjustable` / displaced arrays + `adjust-array`**: on all backends except `--no-gc` (clear compile error there). The fill pointer is the effective length; a displaced array is a bare view. `.kb/adjustable-arrays.md`
- **`read`/`load`/`read-line`/file + string streams**: a runtime reader is emitted into compiled output; a stream is an opaque backend-local integer handle; `print`-family and `format` take stream arguments on all three backends. `.kb/read-load-streams.md`
- **`rontolisp:fetch` (outgoing HTTP)**: interpreter/JVM use `HttpClient`; WASM is component-only. `.kb/fetch-http.md`
- **`rontolisp:http-handler` (incoming HTTP)**: handler takes a request plist and returns a response plist (headers included, both directions); interpreter (JDK `HttpServer`), JVM (generated class implements `HttpHandlerSupport.Handler`, needs the jar on the classpath), WASM `--component` (`wasi:http/handler@0.3.0`, a CALLBACK async lift -- everything base component-model-async, no gated wasmtime feature -- whose response is delivered mid-task via `canon task.return`; run under `wasmtime serve -W gc=y -W exceptions=y`, wasmtime 46+; wasmCloud wash 2.5.2 hosts it via `wash dev`). A handler that awaits must be an `rontolisp:async-defun`. fetch + serve are ONE Lisp library (`http.lisp` over wit-imported `wasi:http@0.3.0`, spliced by `eval/HttpLibrary` following the reachable half). Preview-1 WASM = compile error. Spin cannot run it (no wasm-GC); wasmCloud hosts it (released wash, `dev.wasm_proposals: [gc, exception-handling, component-model-async]`). `.kb/fetch-http.md`, `.todo/51`
- **`rontolisp:tcp-*` / `tls-*` + the `usocket` shim**: socket handles live in the file-stream handle space, so the stream ops work on them. TLS is interpreter/JVM only; WASM tcp is component-only. `.kb/tcp-sockets.md`
- **`java:` interop**: reflection-based, interpreter + JVM only. `.kb/java-interop.md`
- **Time/environment built-ins**: all three backends; WASM returns floats beyond the `i31` range. `.kb/time-environment-builtins.md`

### Backends and flags

- **`--dynamic`**: unresolvable calls/variables fall back to the embedded `eval` instead of a compile error. `.kb/dynamic-late-binding.md`
- **`--optimize`**: tree-shaking post-pass for WASM + JVM; skipped under `--component`. `.kb/optimize-dead-code-elimination.md`
- **`--component` (WASI 0.3)**: the core module stays Preview-1-identical and an adapter implements WASI; `rontolisp:wasm-export` additionally becomes a typed component export (`:async t` for I/O inside an export), and `--emit-wit` writes the component's WIT world next to the `.wasm` (an `am.ik.wit` document model per blob variant — `base`/`sockets`/`http-server`/`nogc`/`nogc-print` — printed canonically; fixtures + generator regen via `src/wasm-component/regen-wit.sh`). `.kb/wasi-component.md`
- **WIT type mapping is settled** (`compiler/WitTypeMapper`, for `wit-import`/`wit-export` todos 126-128): `result<T,E>` = ok value / error arm signals a condition on EVERY backend (todo 124 option (c); the WASM catch mechanism landed with todo 129, so the todo-128 prerequisite is satisfied), `list<u8>` = byte string. Do not re-litigate a cell; it is a user-facing breaking change. `.kb/wit.md`
- **`rontolisp:wit-export` / `--scaffold-wit`** (todo 126): a program declares the WIT world it implements; the world is then the AUTHORITATIVE export list (a hand-written `wasm-export` or a `rontolisp:http-handler` beside it is a compile error), every `defun` is checked against it (name/arity/type/`async`, errors naming the WIT line), and it LOWERS into the equivalent `wasm-export` directives -- so the component is byte-identical and there is no new export path. Only the export side; a world's imports are declared with `rontolisp:wit-import` (below). `.kb/wit.md`
- **`rontolisp:wit-import` / `wit-provide`** (todos 127-128): a program declares the WIT interface it CALLS, and the directive LOWERS per backend -- Preview 1 WASM = one `wasm-import` per WIT function (byte-identical to the hand-written block, still shakeable under `--optimize`, and carrying the FLAT type set only: a core import has no component type to describe a richer shape with); interpreter/JVM = a synthesized `defpackage` + one ORDINARY `defun` per function dispatching through the interface's provider; **`--component` = a real component-model instance import, `canon lower`ed** (the canonical ABI marshals the rich types -- every result shape except `flags`, and, since todo 133, every param shape except `flags` and a `list<T>` other than `list<u8>` -- so the HOST is the provider and the component composes with anyone exporting the interface; a `result`'s error arm crosses as an envelope that a generated Lisp wrapper turns back into `rontolisp:wit-error`; unused members are pruned from the import since this path has no tree shaker; zero imports = byte-identical; on EVERY variant, `rontolisp:http-handler` (serve) included -- which is the only way a served handler keeps STATE, a `wasi:http` host recreating its instance per request, though whether the store survives is the host's business: wasmtime's `-S keyvalue=y` provider is rebuilt per instance, wasmCloud's `wash dev` links an out-of-process one); `--no-gc` = clear error. **The core ships NO provider for any concrete interface** -- it knows the provider MECHANISM only (`rontolisp:wit-provide` binds a plain Lisp callable; an unbound interface signals `rontolisp:wit-error`; a `wit-provide` is dropped as inert on the WASM backends, where the host is the provider). An implementation of a WIT interface is ordinary USER code -- a new host interface must cost a `.wit` file, not core code (`examples/wit/keyvalue` binds the REAL `wasi:keyvalue/store` and runs on the interpreter over a Lisp store, on the JVM over `java:` interop, and as a component against wasmtime's own implementation -- same output, same source). The runtime is `wit.lisp` (a Lisp-source library, not prunable), so no backend gained a case. `WitImportInliner` runs BEFORE `UserMacroExpander` (its synthesized `defpackage` must exist before any call site resolves) -- the opposite of `WitExportInliner`. `.kb/wit.md`
- **`rontolisp:wasm-export` / `wasm-import` / `--no-wasi`**: export a defun as host-callable WASM, declare host functions (Preview 1 only), emit an import-free reactor module. A memory-EXPORTING module (never `--component`, where the canonical ABI does it) also exports the host arena API `__ronto_alloc_mark`/`_reset` on BOTH wasm backends -- but the GC one's reset never pops below the interned-symbol pool's high-water, and has no `--no-gc`-style automatic reset. `.kb/wasm-export-no-wasi.md`, `.kb/wasm-import.md`
- **WASM GC strings**: `TYPE_STRING` lives on the GC heap (`$str_bytes` array); `HEAP_PTR` is a stack pointer, so transient string building no longer grows the linear heap. A string's field 0 is an identity id, **not** a linear offset. `.kb/wasm-gc-strings.md`
- **`--no-gc`**: a separate backend (`NoGcWasmCompiler`) whose value model is unboxed `i64`/`f64`/linear-memory pointers ("non-GC" != "no SIMD"); emits a plain MVP module. Packed arrays are bump-allocated with no free (hence `-into` and `with-arena`). Rejects specials/CLOS/defstruct/arrays/`linalg:`. `.kb/no-gc-scalar-wasm.md`
- **`--simd` is the ONE orthogonal acceleration flag** across every backend (interpreter Vector API, JVM bridge, wasm-GC v128 over `(array (mut v128))`, `--no-gc` v128). It routes the vectorizable `vec:` and `linalg:` kernels to native lanes; a build without it is byte-identical to one that never knew the flag. **Precision contract: an `#f` (single-float) reduction accumulates in single precision on EVERY `--simd` backend** and can differ from the scalar reference by ~the f32 epsilon (the matrix product is exempt). The DEFAULT scalar `vec.lisp`/`linalg.lisp` is the cross-backend byte-identity oracle (ci-spec never passes `--simd`). A `linalg:` kernel is PARTIAL: it returns null = declined and the call site runs the scalar defun. `.kb/vec.md`, `.kb/linalg-simd.md`
- **Destination-passing `vec:` kernels (`vec:add-into` &c)**: every vector-returning kernel has an `-into` sibling writing into a caller-supplied destination. The element-wise ones MAY alias `out` with an operand, `matvec-into` may NOT -- and that alias guard is written three times (`vec.lisp`, `VecSimd`, `JvmSimdVectorTemplate`) because each accelerated call site REPLACES the defun. `.kb/vec.md`

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

A program using `handler-case`/`ignore-errors`/`unwind-protect` compiles in EH
mode, and an ASYNC component (async-defun/async-lambda/await, incl. every
fetch/serve program) forces EH mode too: add `-W exceptions=y` to BOTH wasm
run commands (wasmtime 37+; without it the module fails to parse). A fetch
component additionally needs `-S http=y`. Programs without those forms are
byte-identical to pre-EH output and keep the flags above.

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
- WASM integration tests skipped if Docker unavailable

### After Task Completion

- Format: `./mvnw spring-javaformat:apply`
- Test: `./mvnw test`
- Web profile compile: `./mvnw -Pweb compile`. Required whenever `src/web/java` changed (the `Target_*` substitutions, `web/` `@JS` classes) or a signature it overrides changed. `src/web/java` compiles only under the `web` profile, so `./mvnw test` does NOT catch a break there -- only the web-playground CI job does.
- Native E2E: build the native image and run `CiSpecE2eTest` against it (see above). Required whenever `ci-spec.yaml` or any cross-backend output changed.
- Javadoc: `./mvnw javadoc:jar` - confirm 0 warnings/errors (except for errors about `Version` class)
- Notify: `osascript -e 'display notification "<Message Body>" with title "<Message Title>"'`
</content>
</invoke>
