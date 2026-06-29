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

- **Lisp-2 (separate function/variable namespaces) in all three backends**: A bare symbol is a variable reference only (interpreter: `The variable X is unbound`; compilers: `Cannot compile symbol ...`); a symbol in call position resolves in the function namespace only (a `let`-bound `car` never shadows the function `car`); a function value comes from `(function name)` / `#'name` (a `function` special form; lexer emits `Token.FunctionQuote` for `#'`) or `symbol-function`. `funcall`/`map`/`reduce` accept symbol designators (interpreter: at runtime via `apply`; compilers: a literal `(quote name)` in function position is statically rewritten to `(function name)` by `compiler.FunctionDesignators.normalize`). `defun` defines into the function namespace and returns the name symbol. Interpreter: `Environment` keeps two maps (`lookup`/`define`, `lookupFunction`/`defineFunction`; builtins use `defineFunction`); `LispEvaluator.SPECIAL_OPERATORS` (= `PackageRegistry.specialOperatorNames()`) lists names with no function value (so `#'if` errors). Compilers: Pass 1 collects only real `(defun ...)` (a top-level `(setq f (lambda ...))` binds a variable — call via `funcall`); `Jvm/WasmFunctionFormCompiler` compiles `(function name)`/`symbol-function`. Eval runtimes keep a second function namespace (`_fenv` field on JVM, `GLOBAL_FENV` wasm global). `FreeVarAnalyzer` skips the operator position and `(function name)` designators.
- **JVM Class Version 50 (Java 6)**: Avoids mandatory StackMapTable (required from version 51+). The lenient type-inference verifier is relied on throughout (tolerates dead code after `goto`, needs explicit `checkcast` before `String`/array methods on `Object` slots, and definite-assignment on every path).
- **WASM function types outside rec group**: wasmtime's WASI host requires plain `(func ...)` types for imports. Only the cons struct goes inside a rec group.
- **symbolp/stringp**: Quoted symbols and string literals share runtime representation, distinguished by a leading `"` (`charAt(0) == '"'`).
- **consp in JVM**: Both cons cells and function references use `Object[]`, distinguished by `arr[0] instanceof Integer`.
- **Three-pass compilation**: Pass 1 collects defuns. Pass 2a compiles defun bodies, 2b top-level, 2c iteratively compiles lambda bodies. Top-level must compile before lambda iteration.
- **`do`/`return` and the `%block` non-local exit boundary**: `do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop with parallel-stepped vars (assigned through temporaries). `do`/`dolist`/`dotimes` wrap their expansion in the internal `%block` special form (`LispNames.BLOCK_INTERNAL`, a `CL_INTERNALS` symbol); `return` (`LispNames.RETURN`) is a non-local exit to the **nearest** enclosing `%block`. Per backend: interpreter throws `LispReturnSignal` (stack-trace-free) caught by `evalBlock`; JVM stores the value into a local then `goto`s the block exit (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`) — store-then-jump keeps the operand stack empty at the merge point (version-50 verifier); WASM emits `block (result (ref null eq))` and `return` is a `br` at depth `Ctx.wasmCtrlDepth - marker` (`WasmBlockCompiler`/`WasmReturnCompiler`; `wasmCtrlDepth` is bumped only by `if` (+1) and `while` (+2)). Consequence: `return` works only where the surrounding operand stack is empty (an `if`/`when` branch or a loop-body statement), not mid-expression. `member`/`assoc` are themselves expanded through `do`/`return` with an `(atom cursor)` end-test. The runtime `_eval` interpreters do not know `do`/`return`/`%block` (README).
- **`%` prefix convention**: Internal helpers not part of the public API use a `%` prefix (e.g. `%remf-tail`). Registered in `Environment` with dedicated `Jvm/Wasm<Name>Compiler` classes, but not documented in the README.
- **Built-in function wrappers**: `BuiltinFunctionWrappers` (compiler pkg) generates synthetic `(setq name (lambda ...))` defuns for built-in operators, injected in Pass 1 so `#'+`/`#'car` etc. work as first-class values (the wrapper body uses the operator in call position, where `compileCons` inlines it). User defuns with the same name take priority. This `(setq name (lambda ...))` shape is the wrappers' internal encoding (consumed by `extractSetqLambda`) — user-written top-level setq-lambda is NOT a function definition (Lisp-2).
- **JVM method name mangling**: The JVM forbids `/`, `<`, `>`, `:` in method names. `JvmLispCompiler.mangleMethodName()` maps them to `$div`/`$lt`/`$gt`/`$le`/`$ge`/`$colon`.
- **Packages**: A small namespace system with three built-in packages — `cl` (standard symbols), `cl-user` (default, uses `cl`), and `rontolisp` (does not use `cl`; owns `version` and the `list-*` introspection functions). Implemented as a single read/compile-time pass, `PackageResolver` (root `am.ik.rontolisp`), that runs before the evaluator and both compilers and rewrites every form into a canonical shape the backends already handle (bare names for `cl`/`cl-user` symbols, `pkg:name` otherwise; `*package*` -> quoted current package; `(in-package P)` consumed). The one hard error is an unqualified `cl` symbol in a package that does not use `cl` (`LispPackageException`). `cl`'s symbol set is `PackageRegistry.CL_SYMBOLS` = union of `CL_SPECIAL_FORMS`/`CL_MACROS`/`CL_FUNCTIONS`/`CL_VARIABLES`/`CL_INTERNALS` (single source of truth). Introspection: runtime in `Environment.registerPackages`, compile-time constants in `Jvm/WasmIntrospectionCompiler` (`cl-user` = Pass-1 user defun names via `Ctx.userDefunNames`); shared logic in `PackageIntrospection`. Adding a package is a registry change, not a resolver change. Limitations (README). Tests: `PackageResolverTest` + package cases in the per-backend tests.
- **`read`/`load`, `read-line`, file streams in all three backends**: A runtime reader/parser is emitted into the compiled output (like `eval`). Interpreter uses `LispReader`/`Files`; JVM emits a recursive-descent reader (`JvmReadRuntimeBuilder`) with full JDK parity; WASM (`WasmReadRuntimeBuilder`) walks linear memory, interns symbols to shared string offsets; its integers are `i31` and it parses decimal floats (`emitTryFloat`, no exponent) into `TYPE_FLOAT`. `with-open-file` is a plain macro (`LispMacroExpander.expandWithOpenFile`) over `open`/`close`, so no backend needed a new special form. A stream is an opaque integer handle, backend-local: interpreter/JVM index a stream table, WASM uses the WASI fd directly. **Design decision**: `:direction` must be a literal `:input`/`:output` so both compilers resolve the mode at compile time (`Jvm/WasmOpenCompiler.staticMode`); consequently `open` has no `BuiltinFunctionWrappers` entry. WASM `open`/`load` resolve paths against the first preopened dir (fd 3), so they need `--dir` (in `--component` mode the same `path_open`/`fd_*` imports are satisfied by the adapter over `wasi:filesystem@0.3.0`). The runtime `_eval` interpreters do not know these forms (README).
- **`--dynamic` (late binding) for the JVM/WASM compilers**: Opt-in (CLI `--dynamic`; constructor boolean threaded into both `Ctx`). By default an unresolvable call/variable throws `Cannot compile: <name>`; when set, those sites emit a runtime fallback via the embedded `eval` — `(f a b)` -> `_apply(_eval('(function f), null), (list a b))`, `#'f` -> `_eval('(function f), null)`, bare `x` -> `_eval('x, null)`. Arguments compile normally (enclosing locals stay visible); only operator/variable resolution is deferred. Implemented by `Jvm/WasmDynamicCallCompiler`. Forces `usesEval = true`. Primary use: compile a source that defines functions via `load` without rewriting calls into `(eval ...)`. Tests: `*LispCompilerTest#dynamic*`.
- **`--component` (WASI 0.3 / Preview 3 output) for the WASM compiler**: Opt-in (CLI `--component`; `WasmLispCompiler(dynamic, component)`; threaded as a `component` boolean). Default output stays a Preview 1 core module (no regression). Run a component with `wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y` (wasmtime 46+). **Design**: the rontolisp core module is emitted unchanged from Preview 1 (still imports the eight `wasi_snapshot_preview1` functions, all `FUNC_*` indices stable), and an **adapter** core module implements them over WASI 0.3's `stream<u8>`/`future<T>` + async canonical ABI. The component's `run` export is a **stackful** async `canon lift` (no callback), so the synchronous `stream.*`/`future.*` built-ins block cooperatively and the adapter stays straight-line. **Gotchas to preserve**: `wasi:cli` and `wasi:filesystem` expose DISTINCT `error-code` types -> separate future built-ins (`future-read-cli`/`-fs`); the fs error-code is a string-bearing variant -> `future-read-fs` needs realloc. **Index stability**: WASM static function-import indices and the `FUNC_*` constants in `WasmLispCompiler` are kept identical across modes (preview1-style `random_get`/`clock_time_get`/`environ_*` imports exist in both modes; `WasmRandomCompiler` calls `random_get` in both modes (real host entropy in Preview 1, the adapter's `wasi:random` in component); `WasmTimeCompiler` branches on `Ctx.component`; `FUNC_FETCH` is reserved in both modes). Assembly lives in `WasmComponentBuilder` (codegen.wasm) over `am.ik.wasm.ComponentWriter` (general async-canon-ABI encoder, reusable for future language-level async). The fixed byte blobs (`import-block.bin`, `mem.wasm`, `adapter.wasm`) are loaded from classpath resources under `.../codegen/wasm/component/` and registered for native image in `resource-config.json` (wildcard). **The blobs are generated** from sources under `src/wasm-component/` — to change them follow `src/wasm-component/README.md` (edit sources, run `regen.sh`, re-derive the wiring constants from `wasm-tools dump`, re-test). Encoders pinned by `ComponentWriterTest`; E2E by `WasmLispCompilerIntegrationTest`. Limitations (README "Compile to a WASI 0.3 component").
- **`rontolisp:wasm-export` (host-callable Lisp functions) + `--no-wasi` reactor mode**: `(rontolisp:wasm-export 'name :params '(T...) :returns T)` is a directive in the `rontolisp` package (`LispNames.WASM_EXPORT`, registered alongside `fetch`/`version`/`list-*` as an implementation-specific extension, not CL standard) that exports a top-level `defun` as a host-callable WASM function. **Preview 1 core module only** — no-op on interpreter/JVM (returns the named symbol) and under `--component` (gated by `exportsPresent = (!this.component) && ...`). Type designators bridge the GC calling convention to a host ABI: `:int`<->i31 i32, `:float`<->`TYPE_FLOAT` f64, `:bool`<->nil/t i32, `:string`/`:sexpr`<->`(ptr,len)` in linear memory (via the embedded reader/printer); omitted/nil/`:void` `:returns` = void (no WASM result). Memory types emit a `__ronto_alloc` bump allocator + `_string_from_mem` helper (appended after lambdas at `FUNC_USER_BASE + numDefuns + numLambdas`, so `FUNC_*` indices stay fixed); a data segment seeds `HEAP_PTR_ADDR` so host calls work without `_start` running. Parsing/wrapper emission in `WasmExportCompiler`; helpers in `WasmExportRuntimeBuilder`; wiring in `WasmLispCompiler` (Pass 1 collects `Decl`s out of `topLevelExprs`, validates name-exists/arity, builds `ExportPlan`s). **`--no-wasi`** (CLI `--no-wasi`; `WasmLispCompiler(dynamic, component, noWasi)`; Preview-1-only — silently disabled in component mode) emits a module with **no `wasi_snapshot_preview1` imports** so a host instantiates it with no import object (reactor/library module); because it is a reactor, not a WASI command, its top-level init entry is exported as **`_initialize`** (reactor ABI, host-run once after instantiation) instead of `_start` — the `WasmLispCompiler` export section keys the name off `noWasi`, and it stays a tree-shaker root either way. **Index stability**: the eight WASI import slots (function indices 0-7) are filled with internal `unreachable` trap stubs carrying the SAME type indices the imports used, so every `FUNC_*` constant stays valid (no `Ctx`/expression-codegen change). Pure-compute exports only — any I/O (`print`/`read`/`open`/`getenv`/time/`random`, incl. a top-level form) hits a stub and traps. Tests: `WasmExportCompilerTest` (structural, no Docker), `WasmLispCompilerIntegrationTest` (`wasmtime --invoke`). Limitations (README "Exporting Lisp functions"). Follow-ups in `.todo/20` (component typed exports) / `.todo/21` (memory-ABI CI).
- **`--optimize` (WASM dead-code elimination)**: Opt-in (CLI `--optimize`; `WasmLispCompiler(dynamic, component, noWasi, optimize)`; WASM only — JVM threads it but ignores it). A **post-pass relocating tree-shaker** (`am.ik.wasm.WasmTreeShaker`, language-independent) runs on the finished **core module** bytes in `WasmLispCompiler.compile` just before returning, but is **skipped under `--component`** (the WASI 0.3 adapter binds the core's fixed import/`FUNC_*` layout, so renumbering would break it — the component path stays byte-identical). It parses the module sections, builds a call graph from the actual `call` (and `ref.func`) immediates in every body, computes the functions reachable from the roots (exported functions + `_start`/start section), drops the rest **including unused WASI function imports**, and renumbers every surviving function reference. Reachability is exact, not a manual table: when `eval`/`load`/`apply` is used, the dispatch bodies contain real `call`s to every registered function, so nothing dynamically-reached is pruned. It only renumbers **function** indices — type/memory/global/data sections are copied verbatim, so type indices (and the GC rec-group layout) stay stable. This is the one place the fixed-index invariant is deliberately broken, and only because every `call` site is rewritten in lockstep. **Decoder correctness** rests on the backend emitting (a) no `call_indirect`/element segments — first-class calls go through dispatch functions with direct `call`, so `call` is the only function reference; and (b) a finite, enumerated opcode set (incl. the `0xFB` GC ops and `block (result …)` blocktypes) — an unknown opcode throws rather than emit a corrupt module. With `--no-wasi --optimize` a pure-compute reactor (`fact`) drops ~26 KB -> ~1.3 KB. Tests: `WasmTreeShakerTest` (structural, no Docker: shrinkage, import drop, well-formedness via a mini-parser, idempotence) + optimize cases in `WasmLispCompilerIntegrationTest` (`wasmtime` behavior parity). Limitations (README "Optimize"). Follow-up: JVM method-level DCE (`.todo/22`).
- **`--no-gc` (non-GC scalar WASM lowering)**: Opt-in (CLI `--no-gc`; `ScalarWasmCompiler(optimize)`). A **separate backend class** (`codegen.wasm.ScalarWasmCompiler`, dispatched from `RontoLispCli.compileToFile` for `.wasm` output when `--no-gc` is set), NOT a flag threaded through `WasmLispCompiler` — the GC backend's fixed-`FUNC_*`-index invariant and ~200-function runtime are irrelevant here (no runtime is emitted), so the GC path stays untouched (zero regression risk). It emits a **plain MVP module**: no rec group, no `struct`/`array`/`i31`/`eqref`, no import (and no linear memory unless the program uses strings); instantiates with no import object and runs with **no `-W gc`**. **Value model**: each value's wasm type is chosen by **static type inference** — integers use `i64`, floats use `f64`, strings use `i32` (a linear-memory pointer; see Strings below). Types are inferred by a **monotone fixpoint** (`inferTypes`) over the call graph: exported params are pinned to the boundary designator (`:int`/`:bool`->INT, `:float`->FLOAT), all other param types, **all let/`do`-bound local types** (`Types.locals`, by name per function) and all return types start at INT and only ever widen to FLOAT (a `CallSink` accumulates the join of call-site arg types into callee params; a `setq`/let-init widens the target local's type, so an integer accumulator summed with floats becomes `f64`); recompute until stable. `compileExpr` returns the `Ty` it emitted and consults `staticType` (a frozen, read-only `typeOf`) to insert promotions where INT meets FLOAT (`coerce`: `f64.convert_i64_s` / `i64.trunc_s_f64`); let/`do` locals are allocated at their inferred (possibly-widened) type so `setq` (`local.tee`) stays type-consistent. Using `i64` makes integer arithmetic exact to 2^63 (wider than both the GC `i31` and an all-`f64` lowering's 2^53 — e.g. `a*a-(a-1)*(a+1)` stays exactly 1 past 2^53). `mod`/`rem` are emitted natively per type (INT: `i64.rem_s`, mod = `((a rem b)+b) rem b`; FLOAT: `a-b*floor|trunc(a/b)`); `min`/`max` INT folds via `select` (no `i64.min`); rounding (`truncate`/`floor`/`ceiling`/`round`) yields INT; `sqrt` is `f64.sqrt` (always FLOAT); the integer bitwise ops `logand`/`logior`/`logxor`/`lognot`/`ash` map to `i64.and`/`or`/`xor`/(xor -1)/(`shl` vs `shr_s` picked by `select` on the sign of the shift). **Iteration**: `dotimes`/`do`/`do*` expand (via `LispMacroExpander`) to `let`/`while`/`%block`(`BLOCK_INTERNAL`)/`setq`/`return`, all handled directly — `while` is a `block`/`loop` pair leaving nil (i64 0), `%block` is a **typed** wasm block whose result = join(normal completion, every enclosing `return` value), and `return` is a `br` at depth `Fn.ctrlDepth - blockMarker` (control depth bumped by `if` +1, `while` +2, `%block` +1); `setq` is a `local.tee` to a param/let slot (no globals). **Strings (Phase 2a)**: a string is an `i32` pointer to a linear-memory header `[len:i32 LE][UTF-8 bytes]`. String literals are laid out 4-byte-aligned in a data segment from `STR_DATA_BASE`=8 (so memory addr 0 is always a valid zero-length string = the empty string / nil-in-string-context, used to type-check `cond`'s `(if t body nil)` expansion via `compileCoerced`); `(concatenate 'string ...)` sums operand lengths, bump-allocates `[len][bytes]` via the `__alloc` helper (mut-i32 heap-pointer global 0, grows whole pages) and copies bytes via the `__memcpy` helper (byte loop, no bulk-memory). The two helpers occupy function indices `internalCount+E` / `+1`, appended after the wrappers; the memory + helpers are emitted **only when the module uses strings** (`Mem.used` = any string literal or `:string` boundary type), so a pure-numeric module stays byte-identical to the original. The `Ty.join` lattice: INT doubles as the inference bottom and yields to STRING; FLOAT-vs-STRING is a type error; `coerce`/`compileCoerced` rejects mixing a string with a number (except nil->"" ). `usesMemory` modules also export `memory` and `__ronto_alloc` so a host writes `:string` inputs and reads `:string` results. **Boundary stays host-width**: `:int`/`:bool` are `i32` (as in the GC backend), `:float` is `f64`, `:string` is a `(ptr,len)` i32 pair (param: copied into a fresh internal header via `__alloc`+`__memcpy`; result: internal ptr -> `(ptr+4, load len)`) — wrappers convert host<->internal (`i64.extend`/`i32.wrap`/`f64.convert`/`i32.trunc`), so a returned value outside i32 wraps even though internals are i64. No rational type: `/` is float division and `0` is false in a boolean context (full CL treats only `nil` as false). Both divergences are documented (README "Non-GC Output"). **Scope**: only `(rontolisp:wasm-export ...)` functions with boundary types `:int`(i32)/`:float`(f64)/`:bool`(i32 0/1)/`:string`((ptr,len))/`:void`; top level may contain ONLY defuns + export directives (a pure-compute reactor, no `_start`/`_initialize`). **Pipeline**: (1) `collectCalls` BFS from the export targets validates eligibility (throws `UnsupportedOperationException` naming the offending op + function for cons/char/symbol/hash/`eval`/I/O/`dolist`-or-list-iteration/global-`setq`/free var; string literals + `(concatenate 'string ...)` are eligible; a `setq` target must be a bound param or let/`do` local) and yields the reachable defuns in discovery order, each assigned a stable index (an unreached ineligible defun is silently dropped); (2) `inferTypes` fixpoint; (3) `compileExpr` emits each reachable body + a host wrapper. `collectCalls`, `typeOf` and `compileExpr` share one dispatch shape and all expand the same macros the other backends do (`cond`/`and`/`or`/`when`/`unless`/`let*`/`1+`/`1-`/`zerop`/`plusp`/`minusp`/`evenp`/`oddp`/`dotimes`/`do`/`do*`, variadic comparisons via `expandComparison`) down to the core (`if`/`let`/`progn`/`while`/`%block`/`setq`/`return` + numeric/comparison/bitwise/`not` primitives). Reuses `WasmExportCompiler.parse`/`isExportForm`/`paramWasmTypes`/`resultWasmTypes` + the `T_*` constants (same package). Internal functions are `(i64|f64...)->i64|f64` (inferred); wrappers convert the host ABI (`:int`/`:bool` i32, `:float` f64) to/from it. Composes with `--optimize` (`WasmTreeShaker` is GC-agnostic and already decodes the memory/global/`memory.grow`/`block`/`loop` opcodes the string path emits); rejected with `--component` (GC-bound) in `RontoLispCli`. Tests: `ScalarWasmCompilerTest` (structural, no Docker: MVP shape via a mini-parser, eligibility errors, string memory/data/export sections) + `--no-gc` cases in `WasmLispCompilerIntegrationTest` (`wasmtime --invoke` **without `-W gc`**, parity with interpreter, incl. `noGcSupportsStringConcatenationAtTheBoundary` asserting the returned `:string` length); the `:string`-parameter side of the ABI needs a memory-writing host (Node/playground), exercised by `examples/mandelbrot-nogc.lisp`. Example: `examples/mandelbrot-nogc.lisp` returns the rendered grid as a `:string`. Remaining Phase 2 follow-up: `:sexpr` (cons/reader/printer runtime) still deferred (`.todo/23`).
- **Time / environment built-ins (`get-universal-time`, `get-internal-real-time`, `get-internal-run-time`, `getenv`)**: implemented in all three backends. Interpreter/JVM (`JvmTimeCompiler` via a `systemOps` methodref map on `Ctx`; `JvmGetenvCompiler`) return integers; WASM (`WasmTimeCompiler`) reads WASI `clock_time_get` and returns a **float** because the magnitudes exceed the `i31` range. `getenv` on WASM uses a `_getenv` runtime helper (`WasmGetenvRuntimeBuilder`) scanning the WASI environ buffer. `get-universal-time` is seconds since the 1900 CL epoch (Unix + 2208988800). Registered in `LispNames`/`PackageRegistry.CL_FUNCTIONS`. The WASM clock/environ imports exist in both modes (Preview 1 -> real host; component -> adapter over `wasi:clocks@0.3.0`/`wasi:cli/environment@0.3.0`), keeping import indices identical.
- **`rontolisp:fetch` (outgoing HTTP, JS `fetch`-style)**: a `rontolisp`-package function (not CL standard). Behavior, options, and limitations (README "HTTP requests"). Interpreter (`eval/HttpSupport.java`) and JVM (`JvmFetchRuntimeBuilder`) use the JDK `java.net.http.HttpClient`. **WASM is component-only** (`WasmFetchCompiler` throws in Preview 1 mode — there is no host `wasi:http` for a core module). **Hybrid**: a fetch component keeps base I/O on WASI 0.3 but adds the WASI 0.2 http machinery (`wasi:http@0.2` + `wasi:io@0.2`, driven synchronously by `pollable.block`), because async `wasi:http@0.3` does not exist upstream yet (see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md` for the upgrade path). So a fetch component needs `-S http=y` in addition to the async flags; non-fetch components don't import `wasi:http`. To avoid forcing `-S http=y` on every component, the http machinery lives in a parallel blob set (`import-block-http.bin`/`mem-http.wasm`/`adapter-http.wasm`, sources `uni-http.wit`/`core-http.wat`/`mem-http.wat`/`adapter-http.wat`, `deps/*-0.2` + `deps/http`); `WasmComponentBuilder.build(core, usesHttp)` -> `buildHttp`, emitted only when the program uses fetch. The rontolisp core imports one version-agnostic `http.fetch` seam (module "http", name "fetch", 12 i32 params); only `adapter-http.wat` + `import-block-http.bin` + `buildHttp` bind to a WASI http version (so the future 0.3 upgrade is isolated). The whole outgoing-request state machine lives in `adapter-http.wat`; the core serializes headers and rebuilds the result plist via `WasmFetchRuntimeBuilder` helpers; `:method` is resolved statically by `WasmFetchCompiler.methodDiscriminant` (unsupported literal = compile error; runtime-computed = GET). Regenerating/re-wiring follows `src/wasm-component/README.md`. **Browser playground**: `java.net.http` can't be GraalVM Web Image-compiled, so a web-profile substitution (`src/web/java/.../eval/Target_HttpSupport.java`) routes fetch to a synchronous `XMLHttpRequest` (`web/BrowserHttp.java`, `@JS`), subject to CORS. Tests: interpreter/JVM use a local `HttpServer`; `WasmLispCompilerIntegrationTest` has deterministic error-path + `-S http` gate tests plus an opt-in (`RONTOLISP_HTTP_E2E=1`) success test.
- **`eval` in all three backends** (interpreter, WASM, JVM): a runtime tree-walking interpreter sharing the compiled value representation (`null`=nil, `Long`=int, `Double`=float, `String`=symbol or `"..."`-prefixed string, `Object[2]`=cons, `Object[]` with an `Integer` head=function value; interpreted closures use the sentinel `funcId == -1`). Interpreter: a `LispFunction` registered in `LispEvaluator`'s constructor (avoids a circular dep). WASM (`WasmEvalRuntimeBuilder`/`WasmEvalCompiler`) and JVM (`JvmEvalRuntimeBuilder`/`JvmEvalCompiler`) mirror each other: five functions `_lookup`/`_env_lookup`/`_eval`/`_apply`/`_store` plus a persistent top-level env (`GLOBAL_ENV` wasm global / `_genv` JVM field). Emitted only when the program calls `eval` (`programUsesEval`); WASM keeps stubs to hold fixed function indices, JVM needs none (methods are by name). WASM trick: the `StringTable` dedups strings, so a quoted symbol and a `let`/`lambda`/`setq` name share one data offset and lookup is an `i32` offset compare; JVM uses `String.equals`/`instanceof` directly. `setq`/`setf`/`push`/`pop` all delegate to `_store`. **Top-level global mirroring**: when `usesEval`, a top-level `setq`/`defvar`/`defparameter`/`defconstant` in compiled code (the `Ctx.topLevel` context) also calls `_store(name, value, genv)` to copy the binding into the eval global env, so an eval'd expression can resolve a global the compiled program defined (`Jvm/WasmSetqCompiler.mirrorTopLevelGlobal`; the compiled value still lives in a `main`/`_start` local, the mirror is write-through one-way). Supported forms and the limitations are identical across WASM/JVM and listed in the README "Compiled `eval` limitations".
- **Hash tables**: interpreter/JVM use a real `LispHashTable`/`HashMap` (O(1)); WASM is a true open-chaining hash table (`WasmHashTableCompiler`), not the old O(n) alist. A table is a `TYPE_CELL` box (so `consp` is nil and `hash-table-p` is a `ref.test TYPE_CELL`) holding a header `cons (count . buckets)`: `count` is an i31 of live entries (O(1) `hash-table-count`), `buckets` is a `TYPE_HASH_BUCKETS` array (`array (mut (ref null eq))`, index 33, a bare array comptype after `TYPE_CHAR`; implicitly `<: eq` so it stores in a cons field). Each bucket slot is a `(key . value)` alist or nil; a key's slot is `(_hash(key) & 0x7fffffff) % capacity`. **Invariant**: `_hash` (`FUNC_HASH`, always emitted, signature `((ref null eq)) -> i32` = `TYPE_RAT_GET`) must agree with `_equal` — equal keys hash equal; it folds i31/char/string-offset/float-bits/ratio and recurses on conses, with a constant-0 fallback for identity-compared values (e.g. closures). Keys are still compared with `_equal` within a bucket. `puthash` grows (doubles, `FUNC_HASH_RESIZE`) past load factor 0.75. `FUNC_HASH`/`FUNC_HASH_RESIZE` sit just before `FUNC_USER_BASE`; both are present in Preview 1 and `--component` (no import/`FUNC_START` index shift, so the component blobs are unaffected). `maphash` order is unspecified (README).

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
to GitHub Pages by `.github/workflows/pages.yaml`. The site reuses the browser
playground's WebAssembly runtime, so the Lisp examples are runnable in-page.

**Layout.** `doc/en/nav.yaml` = the sidebar/order (Getting Started, Compiling,
Language Reference, Guides). `doc/assets/docs.css` + `docs.js` = shared theme and
the runnable-cell wiring. `doc/<lang>/` is a language; `en` and `ja` both exist
today, each with its own `nav.yaml`, and docgen renders one site per language
(`/docs/en/`, `/docs/ja/`) with an automatic language switcher in the header
(2+ languages -> switcher appears; `en` is the default and gets the `/docs/`
redirect). Adding another `doc/<lang>/` with a `nav.yaml` auto-creates its site.
Per-operator reference pages live in catalog directories, each with a
`_catalog.yaml` (categories -> ordered `{slug, name}` entries) and a table
"index page": `reference/functions/` (index `reference/functions.md`),
`reference/macros/` (index `reference/macros.md`), `reference/special-forms/`
(index `reference/special-forms.md`). docgen discovers every `_catalog.yaml`,
renders one HTML page per entry with prev/next, and links each operator name in
the index table to its page.

**Code-fence conventions** (parsed by `DocExamplesTest` and the docgen
`RunnableBlockTransformer`):
- ` ```lisp ` = a runnable, self-contained example (becomes a "Run" cell). It is
  executed by `DocExamplesTest` and must not throw. Annotate the final form with
  `; => value` (prin1 form) to show + assert its result; or follow the block with
  a plain ` ``` ` output block to assert stdout (use this for printing examples).
- ` ```console ` = a static transcript or an example that needs stdin/files/network
  or that signals (`read`, `open`, `load`, `with-open-file`, `error`,
  `rontolisp:fetch`). Not executed.
- ` ```bash ` = shell commands. Plain ` ``` ` = expected output.
- Do NOT use dotted-pair literals (`'(a . 1)`) in `lisp` blocks -- the reader
  rejects them; build with `(cons ...)`/`(list ...)`.

**Keep all languages in sync.** Every doc change must be mirrored across BOTH
`doc/en/**` and `doc/ja/**` (and any future `doc/<lang>/`) in the same commit:
adding/removing/renaming a page, a `nav.yaml` entry, or a `_catalog.yaml` entry
must happen in every language tree, and prose edits must be translated. The two
trees must stay structurally identical -- same file set, same heading layout, and
**byte-identical fenced code blocks** (`lisp`/`console`/`bash` and their `; =>`
annotations / output blocks); only prose, headings, link text, `nav.yaml`
`title:`/`lang_name:`, and `_catalog.yaml` category `title:`s are translated
(slugs and operator `name:`s stay identical). Note `DocExamplesTest` only
executes `doc/en` examples, so a broken `doc/ja` code block will NOT be caught by
the build -- this is why ja code fences must be copied verbatim from en.

**Adding/editing pages.** Edit the Markdown; add new top-level pages to
`doc/en/nav.yaml` (and `doc/ja/nav.yaml`). For a new function/macro/special form,
add a per-operator page + a `_catalog.yaml` entry under the matching directory in
each language tree (see "Implementation Order" step 5). After editing examples,
normalize the shown results to the real interpreter values and catch any
non-runnable example:

```bash
./mvnw -Drontolisp.doc.fix=true -Dtest=DocExamplesTest#fixDetailResults test  # rewrite ; => / output of detail pages
./mvnw -Dtest=DocExamplesTest test                                            # verify every example runs + matches
```

**Build & preview locally** (the docs build is plain Java; the playground build
needs GraalVM + Binaryen and only matters for actually running the cells):

```bash
./mvnw -Pweb -DskipTests package                                  # (optional) refresh web/dist/rontoplayground.js(.wasm)
./mvnw -f docs-tool/pom.xml -DskipTests package                   # build the docgen jar
java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs
cd web/dist && jwebserver -p 8000                                 # open http://localhost:8000/docs/
```

In CI, `pages.yaml` builds the playground (`-Pweb`) first, then the docs into the
same `web/dist` (never deleting it), then deploys -- so the deployed playground
wasm and the docs come from the same commit (introspection examples like
`rontolisp:list-macros` therefore agree in the deployed site even if a local
`rontoplayground.js.wasm` is stale). The docgen and `DocExamplesTest` are also
exercised by `./mvnw test`; the `-Drontolisp.doc.fix=true` helper is manual-only.

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
- `src/test/resources/ci-spec.yaml` is the single source of truth for the cross-backend E2E test (`CiSpecE2eTest`, package `am.ik.rontolisp.e2e`). Each case is `name` + `source` + `expected` (with an optional `expectedByBackend` override). Cases share global state and run IN ORDER: the driver concatenates them into one program, runs the native binary once per backend (interpreter / JVM / WASM Preview 1 / WASM component via `--component`), and slices the output back per case. The `WASM_COMPONENT` backend reuses each case's `expected` because the program is deterministic (no file I/O / random / time). The driver only runs when `-Drontolisp.binary=<path>` is set, so `mvn test` on the JVM skips it.
- Documentation lives in `doc/en/**` (Markdown). Runnable `lisp` examples there are verified by `DocExamplesTest`; the site is built by `docs-tool/` (`./mvnw -f docs-tool/pom.xml package`, then `java -jar docs-tool/target/rontolisp-docgen.jar --source doc --out web/dist/docs`) and published by `.github/workflows/pages.yaml`. A `lisp` block followed by a plain output block asserts that output; REPL transcripts use `console`, shell uses `bash`.
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
