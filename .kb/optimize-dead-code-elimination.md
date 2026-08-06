# `--optimize` (levels; dead-code elimination, WASM + JVM)

Opt-in (CLI `--optimize[=LEVEL]`; `WasmLispCompiler(dynamic, component, noWasi, optimize)` / `JvmLispCompiler(className, dynamic, optimize)` / `NoGcWasmCompiler(optimize)`, all taking a `compiler.OptimizeLevel`).

## The levels

**Invariant: the bare `--optimize` is `DEFAULT` and emits exactly the bytes it always did.** It is in every doc page, every `.kb` passage, the CI jobs, the examples and the README, so it is not a level that may be redefined later. Pinned by `OptimizeLevelTest.theBareFlagIsTheDefaultLevel` (`parse("")` -> `DEFAULT`) plus `CliOptionsTest.theBareFlagKeepsItsEmptyValue`; measured once directly, `postgres-hello --component` at `--optimize` and at `--optimize=default` byte-identical at 8,033,507.

| spelling | `eliminatesDeadCode()` | `prefersSizeOverSpeed()` |
| --- | --- | --- |
| (flag absent) — `NONE` | no | no |
| `--optimize`, `--optimize=default` — `DEFAULT` | yes | no |
| `--optimize=size` — `SIZE` | yes | yes |

Those two predicates ARE the level: `OptimizeLevelTest.everyLevelIsDistinguishableAndOnlyNoneHasNoSpelling` fails if two levels answer both the same way, which is the rule "**do not ship a level that is an alias of another**" made mechanical. It is why there is no `high`: nothing in the compiler is held back for being too aggressive, so a third level would have nothing to switch on, and a synonym would teach a reader that levels are decoration. An unknown value is an `IllegalArgumentException` naming the accepted set, not a silent fallback.

**Why a VALUE rather than a second flag.** A separate `--optimize-size` / `-Os` sitting next to `--optimize` does not say how the two relate — a reader hitting it in a build script cannot tell whether it replaces `--optimize`, adds to it, or contradicts it. A value cannot be read that way. The CLI consequence is that `--optimize` stays in `CliOptions.noValueKeys` and the parser learned the `--key=value` form instead (`CliOptions.build`): moving it out of that set would make `rontolisp app.lisp --optimize -o out.wasm` read `-o` as the level.

### What `SIZE` declines, and what that costs

Only the wasm-GC backends (Preview 1 AND `--component`) have anything to trade: the two emissions that deliberately spend bytes on speed, both **on even without `--optimize`** — integer expression-tree fusion (`.kb/wasm-int-fusion.md`; a fused site emits its tree TWICE, raw plus the generic fallback) and unboxed dual-representation locals (`.kb/wasm-unboxed-locals.md`). One predicate switches both: `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, read at the three fusion entry points and at `WasmLetCompiler`'s eligibility scan. The JVM backend and `--no-gc` accept the level and emit byte-identical output (pinned by `theSizeLevelIsADocumentedNoOpOnThisBackend` in `JvmLispCompilerTest` and `NoGcWasmCompilerTest`) — accepted rather than rejected so one build script can pass it for every target.

Measured 2026-08-06, `--optimize` vs `--optimize=size`, wasmtime 47.0.2 (best of three):

| program | default | size | run time |
| --- | --- | --- | --- |
| `examples/db/postgres-hello` (`--component`) | 8,033,507 | 6,408,277 (**-20.2%**) | — |
| `examples/asdf/ironclad-demo` (SHA-256/HMAC/PBKDF2, 4096 rounds) | 2,075,455 | 1,560,097 (-24.8%) | 1.38 s -> 5.21 s (**3.8x**) |
| `examples/ml/nn-vec` (`vec:` kernels) | 288,576 | 231,533 (-19.8%) | 1.07 s -> 1.26 s (+18%) |
| `examples/ml/mlp` (float, no `vec:`) | 177,173 | 142,943 (-19.3%) | 5.58 s -> 6.06 s (+9%) |
| `examples/ml/heat3d` (`linalg:` stencil) | 200,051 | 157,623 (-21.2%) | 0.03 s either way (too short to resolve) |
| the whole `ci-spec.yaml` corpus (319 cases) | 6,070,518 | 5,040,437 (-17.0%) | 2,132 output lines, byte-identical, exit 0 at both |

The run-time column is why the level must be asked for and why the docs carry it beside the size one. Note what the spread says: the SIZE win barely varies (-17% to -25% across every program measured, library-heavy or not), while the run-time price varies more than thirtyfold (+9% to +280%), because only INTEGER arithmetic fuses -- a `vec:`/`linalg:` kernel pays it on its loop indices, an ironclad round pays it on every operation. So the level's cost cannot be stated as one number, and a program that is not integer-hot gets the size win nearly free.

### Why the two trades are ONE level and not two switches

Measured on `postgres-hello --component`, all four combinations:

| fusion | unboxed locals | bytes | vs default |
| --- | --- | --- | --- |
| on | on (`=default`) | 8,033,507 | — |
| on | off | 6,973,056 | -13.2% |
| off | on | 7,096,879 | -11.7% |
| off | off (`=size`) | 6,408,277 | **-20.2%** |

The separate savings sum to 24.9 points against 20.2 combined, so they overlap — but the decisive row is "off/on": it is **dominated on both axes**. Larger than `=size`, measured above; and necessarily slower than `=default`, because an unboxed local's whole payoff is being read raw inside a fused tree and stored raw from one — with fusion off the arithmetic is generic anyway, and every assignment additionally bails into the boxed shadow while every read goes through `_ub_read`, which `=size` does not pay either. A configuration nobody can want does not deserve a spelling.

`-Drontolisp.debug.norawlocals=true` still switches the unboxed locals alone; it exists for A/B profiling and is what produced the "on/off" row above (the "off/on" row needed a throwaway local patch, since no shipped switch produces it).

## WASM

A **post-pass relocating tree-shaker** (`am.ik.wasm.WasmTreeShaker`, language-independent) runs on the finished **core module** bytes in `WasmLispCompiler.compile` just before returning — **including under `--component`**, where it runs right after `WasmImportInjector.inject` and before the component wrapper is built (see "Why the component path is safe" below; it was skipped there until todo-259, on a constraint that turned out not to exist). It parses the module sections, builds a call graph from the actual `call` (and `ref.func`) immediates in every body, computes the functions reachable from the roots (exported functions + `_start`/start section), drops the rest **including unused WASI function imports**, and renumbers every surviving function reference. Reachability is exact, not a manual table: when `eval`/`load`/`apply` is used, the dispatch bodies contain real `call`s to every registered function, so nothing dynamically-reached is pruned. It only renumbers **function** indices — type/memory/global sections are copied verbatim, so type indices (and the GC rec-group layout) stay stable. This is the one place the fixed-index invariant is deliberately broken, and only because every `call` site is rewritten in lockstep.

### Owned data segments (todo-268 groundwork, landed 2026-08-06)

The data section is copied verbatim EXCEPT for segments the compiler declares as owned:
`WasmTreeShaker.OwnedDataSegment(segmentIndex, ownerFuncIndices)` names a segment whose
bytes are referenced exclusively by the given functions, and `shake(module, owned)` drops
the segment when every owner is unreachable. The shaker cannot verify the exclusivity
claim (a linear-memory reference is an indistinguishable `i32.const`), so the claim is
the CALLER's invariant — today's only claimants are the two Unicode case-fold range
tables (~16.4 KB combined, `WasmCaseFoldRuntimeBuilder`), each emitted as its own active
segment at the same addresses `appendBlob` used to give them, owned by `_char_upcase` /
`_char_downcase` respectively (`WasmLispCompiler.compile`, "caseFoldSegments"; owner
indices are shifted by the injected host-import count because the shake runs after
`WasmImportInjector`). Dropping leaves an all-zero hole in linear memory that nothing
reachable reads; segment indices carry no other references because the backend emits no
bulk-memory ops. Pinned by `WasmTreeShakerTest.orphanedCaseFoldTableSegmentsAreDropped`.

### Literal print specialization (same session)

`(print <string-literal|integer-literal>)` (no stream argument) does not call
`FUNC_PRINT_VAL`: the readable form is a compile-time constant (`print` always escapes;
every printer-control variable that could change the text is inert —
`.kb/pretty-printer.md`), so `WasmPrintCompiler.compileLiteralPrint` interns the
pre-rendered form and writes it through `FUNC_WRITE_STR`, which keeps the
`*standard-output*` redirect semantics. The `print-object` hook cannot fire on this path
(it is inside `compilePrintOperator`'s print-object-free gate). The point is
reachability: the generic printer's integer arm alone pins the whole bignum print chain
(9 functions), plus the f64 renderer and ratio accessors — for a program that only
prints literals, all of it now shakes out. An escape-free string literal's rendered form
re-uses the literal's own interned bytes, so the fold costs no data.

**Combined effect, measured 2026-08-06** (`(print "Hello World!")`, wasmtime 47):
Preview 1 `--optimize` 22,355 -> **1,886 bytes** (the two changes: -16,368 data,
-4,078 code); `--component` 29,430 -> **8,930** (shaken core 1,893 + the P1 adapter
module 3,624 + component metadata — the adapter/surface half is `.todo/268`'s third
section). What remains in the P1 module is dominated by the verbatim-copied type section
(578 B) and the string blob (871 B, ~78% of it the FIND-PACKAGE wrapper's package-alias
table interned by Pass 2a before the wrapper dies) — both are `.todo/268`.

**Decoder correctness** rests on the backend emitting (a) no `call_indirect`/element segments — first-class calls go through dispatch functions with direct `call`, so `call` is the only function reference; and (b) a finite, enumerated opcode set (incl. the `0xFB` GC ops, the `0xFD` fixed-width SIMD ops — `skipSimd`, needed since the `--no-gc` `vec:` kernels emit `v128`/`f64x2`/`f32x4` — the `0xFC` misc-prefix saturating truncations the float->integer conversions emit, and `block (result …)` blocktypes) — an unknown opcode (or SIMD sub-opcode) throws rather than emit a corrupt module. With `--no-wasi --optimize` a pure-compute reactor (`fact`) drops ~26 KB -> ~1.3 KB. Tests: `WasmTreeShakerTest` (structural, no Docker: shrinkage, import drop, well-formedness via a mini-parser, idempotence) + optimize cases in `WasmLispCompilerIntegrationTest` (`wasmtime` behavior parity, incl. `--no-gc --optimize` f64x2/f32x4 vec kernels).

### Why the component path is safe (todo-259)

Every core <-> component linkage is **by name**, in both directions, so renumbering the core's functions is invisible to the wrapper:

- the wrapper reaches into the core only through `alias core func (instance N) "name"` (`ComponentWriter.aliasCoreFunc`, encoded as `sort=core func, target=0x01 <instance> <name>`) — `run`, `handle`, `async_cb`, each `wasm-export` wrapper, `cabi_realloc`, `cabi_post_*`. **All of them are core EXPORTS, hence already shaker roots**, including the two the core never `call`s itself (the serve `handle` and its callback `async_cb`, reached only from the `canon lift ... async (callback ...)` declaration);
- the core's imports are satisfied by `core:instantiate <module> vec((name, instanceidx))` with per-interface instances built `from-exports` as `(field name -> func)` maps, so a dropped function import just leaves one unused name in the map — nothing is positional;
- the Preview-1 adapters (`adapter.wat`, `adapter-http-server-p1.wat`) never reference the core at all: they are instantiated BEFORE it and the core binds them by name. (The retired claim in this file said the opposite.)

`WasmComponentBuilder.memModuleFor` reads the core's `mem`/`memory` **memory** import, which the shaker keeps verbatim along with every other non-function import.

Effect: a non-serve component is where the shaker earns its keep (`(print "hi")`: 357 KB -> 29 KB), because such a program never reaches the arity dispatch. A **serve** component was long the counter-example (the Clack model `funcall`s the handler, so the dispatch bodies were live and they `call`ed every registered builtin wrapper — a ~4% drop): each of the three rontolisp-owned gate blockers had to be retired first, the keyword-intern exemption below being the last. With it (todo-260, 2026-08-06) the trivial serve component is 594,477 no flag / **280,256** at `--optimize` (-52.9%; 54 of 367 defuns dispatchable) / 225,683 at `--optimize=size`. Note what the bytes do NOT buy on wasmtime serve: rps at `--max-instance-reuse-count` 1 and 128 is unchanged within noise vs the 641,599-byte pre-fix module (measured 2026-08-06, best of three: ~1760 vs ~1780, ~4900 vs ~4990) — the module is compiled once per server run, so per-instance cost is the pre-grow plus fixed instantiation work, not module bytes. The size win is transfer, disk, and compile-time cold start (wasmCloud-shaped hosts), not the reuse loop.

## The funcall-dispatch gate (what makes `--optimize` reach library code)

**A function gets an arity-dispatch case, and a `_lookup` registry row, only when the program can actually reach it as a function VALUE.** Without this the shakers are nearly inert on any program that loads a library: the ladders `call` every registered function, so everything is reachable and `--optimize` on a `(ql:quickload "cl-postgres")` component dropped **22 of 2618 functions (-1.5%)**. With it, an `md5` program drops **-49.3%** (1,177,653 -> 597,641).

`WasmLispCompiler.dispatchableFuncIds` / `JvmLispCompiler.dispatchableFuncIds` compute the set; `WasmRuntimeBuilder.buildDispatchBody` and `JvmRuntimeBuilder.buildDispatchMethods` filter their targets by it, and the registry (the WASM data blob / `JvmEvalRuntimeBuilder.lookupSegments`) filters its rows by the SAME set — they are computed together precisely so they cannot drift: a row whose funcId has no case would resolve and then fall through to the ladder's default arm.

Two sources, both EXACT rather than heuristic:

- **`Ctx.valueFuncIds`** — the funcIds Pass 2 actually materialized as a closure: `WasmFunctionFormCompiler.compileNamed` / `JvmFunctionFormCompiler` (`#'name`), `WasmLambdaCompiler.emitClosureValue` / `JvmLambdaCompiler` (every `(lambda ...)` value), and `WasmAsyncEmit`'s waiter closure over a resume function. Collected DURING emission, not from a pre-scan, which is the whole point: a `#'identity` or `%seq-string` reference a macro synthesizes during Pass 2 is invisible to any scan of the source program, and that is exactly what `.todo/260` recorded a naive attempt dying on. Every body is emitted before the ladders are built, so the set is complete when it is read.
  **Trap, and it bit once:** `WasmAsyncEmit.freshCtx` rebuilds a `Ctx` field by field and also builds the SYNCHRONOUS top level. Omitting `valueFuncIds` there silently lost every closure the top level makes, and `(funcall f 1)` trapped. Any module-wide MUTABLE `Ctx` field must be listed there.
- **the names a runtime SYMBOL designator can resolve.** `_lookup` matches interned offsets (WASM) / string constants (JVM), so a registry row is reachable only when the program already put that exact name there for another reason — a quoted symbol, a string literal, an `intern` of a literal. `StringTable.isInterned` and `ConstantPool.hasStringConstant` are the two probes, and the name is tried three ways: canonical, the `::`->`:` alias row's spelling, and the bare member name after the last colon.

**The gate turns itself off entirely** (every function stays dispatchable) under `--dynamic` and whenever `compiler/RuntimeNameProducers.anyNameResolvable` holds — the program contains `eval`/`read`/`read-from-string`/`load` (it can evaluate data) or `intern`/`find-symbol`/`make-symbol`/`symbol-function`/`fdefinition`/`fboundp`/`uiop:symbol-call` (it can build a name). That class is shared by both backends on purpose: a name that stops resolving on one has to stop resolving on the other. Compile with `-Drontolisp.debug.dispatchgate=true` to have the offending operator NAMED, and to see how many functions stayed dispatchable.

**The one form the scan skips is rontolisp's own** (`RuntimeNameProducers.isCompilerScaffolding`): the generated slot-name fold `(defun %slot-name-key (n) (intern (symbol-name n)))`, which every runtime-slot-name dispatcher (`%slot-value-runtime` & co.) calls on its name argument. It re-spells a symbol the program already holds and its result reaches nothing but a `member` test. It is matched by structural equality against `LispMacroExpander.slotNameKeyDefun()` — the builder that emits it — so an edit there cannot leave a stale pattern here, and a user-written defun of that name with any other body is scanned normally. The fold used to be inlined in each dispatcher; it was pulled out into one named defun precisely so the exemption could be an identity rather than a shape. Boundary: a program that calls `%slot-name-key` itself and funcalls the answer forges a name the gate no longer sees — the same carve-out (and the same `--dynamic` escape) the gate already documents for a name assembled out of computed strings.

**The scan also exempts the keyword-package intern** (todo-260): an evaluated `(intern NAME :keyword)` — judged by the same predicate the compile-path lowering branches on (`LispMacroExpander.isKeywordPackageDesignator`), so the exemption covers exactly the forms that lower to `internKeywordForm` — is not a name producer, whatever NAME computes. This one is a SHAPE, not an identity, and it is sound against the probes rather than by intuition; the reasoning, so the next visitor can re-check it: (a) the lowering spells the result `":" + NAME`; (b) the runtime match is exact-spelling equality against the registry's row keys (offset equality on WASM — `_intern` dedupes against the static table, so equal spelling = equal offset); (c) no row key can begin with a colon, because a keyword can never name a function on any backend — the defun's implicit block rejects it (`BLOCK: block name must be a symbol, got :FOO`, `LispMacroExpander.blockName` / `LispEvaluator.blockName`). The earlier worry that the bare-member probe (probe three) reaches a keyword ran the probe backwards: that probe widens which functions GET a row from pool strings, it never strips the designator's spelling at run time, so `:CAR` still fails to match the row `CAR`. Funcalling a runtime-built keyword is therefore an undefined-function error with the gate on, off, or absent — behavior does not move. Inside QUOTED data the shape stays a trigger (the `intern` symbol itself could be extracted and funcalled). This is what re-opened the gate for the serve component: the HTTP libraries' `%http-method-keyword` / `%http-protocol-keyword` / `%serve-method-keyword` intern runtime method strings into `:keyword`, and were the third rontolisp-owned blocker after the `~/name/` renderer arm and the slot-name fold. Pinning tests: `keywordInternDoesNotHoldTheFuncallDispatchGateOpen` (WasmTreeShakerTest), `keywordInternDoesNotHoldTheDispatchGateOpen` (JvmClassShakerTest, incl. the quoted-data conservatism), `keywordInternStaysInternedInAGateShakenModule` (WasmLispCompilerIntegrationTest).

Without the bail the gate is not sound, and the failure is a trap rather than a diagnosis — measured: 32 tests across both backends, every one of them a name assembled at run time (`(eval (read))`, `(intern "EX-FN" :pkg)`, `uiop:symbol-call`).

**What the bail costs, measured 2026-08-06** (`--optimize`, wasm-GC), after todo-261 retired the two blockers rontolisp itself was contributing:

| program | before todo-261 | after | gate |
| --- | --- | --- | --- |
| `md5` via `ql:quickload` | 597,641 | 597,641 | applies |
| pure compute (no library) | 25,201 | 25,201 | applies |
| `split-sequence` | 619,722 | **234,745 (-62.1%)** | applies |
| `cl-ppcre` | 2,419,247 | **1,890,497 (-21.9%)** | applies |
| `com.inuoe.jzon` | 1,432,415 | 1,414,105 | bails |
| `examples/db/postgres-hello` (`--component`) | 8,085,309 | 8,033,507 | bails |

Both columns are the same probe program measured on the same day; the `jzon` and pure-compute rows sit at a different absolute size than todo-260's table because the probe programs are not the same ones (a row is comparable across its own two columns, not across tables).

The two blockers were BOTH rontolisp's own code, and each masked the next:

1. the spliced runtime `format` renderer's `%fmt-function-designator` (the `~/name/` directive resolves its target out of the control string and funcalls it), now split into a separately-injected arm — `.kb/format.md`, "The `~/name/` arm is injected SEPARATELY";
2. with that gone, the generated slot-name fold's `intern` became the blocker for `cl-ppcre` (worth the whole -21.9% above) — now the scan's one exemption, above. Its earlier rejection was recorded as "harmless, but not what holds the gate open"; retiring blocker 1 retired that reason, which is why it was re-taken in the same session.

The two rows that still bail do so **correctly** — the forge is in the library, not in rontolisp:

- `jzon` calls `(fdefinition key-fn)` on a runtime designator (`src/jzon.lisp`);
- `postgres-hello`: `cl-postgres::initiate-ssl` does `(setf make-ssl-stream (intern (string '#:make-ssl-client-stream) :cl+ssl))` and funcalls it. Dead at run time (guarded by a `find-package :cl+ssl` that fails here), but a trigger-shaped gate cannot see that. **Its `read` half is gone for a different reason**: the one `read` in that whole program was ironclad's `array-reader`, a `#@` reader macro registered with `set-dispatch-macro-character` — a registration rontolisp's reader can never fire. `LibraryDefunPruner` and `expandSetDispatchMacroCharacter` now both skip the `#'name` hook argument (`LispMacroExpander.isDeadReadtableHook`), so the defun is pruned and the reader runtime is not emitted at all. The todo's "postgres-hello needs both halves fixed" was therefore an incomplete diagnosis: three causes, two now gone, the third genuine.

One refinement was tried and REJECTED on measurement (do not re-propose without new numbers): judging the `intern` ARGUMENT shape (shrank nothing — every real program reaching there computes the name anyway — and broke `internIntoALiteralPackage` on both backends, because the two-argument lowering folds the literal into the qualified symbol before either probe can see it).

Tests: `componentCoreIsTreeShakenUnderOptimize` (shrinkage + a scalar and a string-returning export invoked under wasmtime, i.e. the canonical-ABI helpers survived) and `optimizedServeComponentStillServesUnderWasmtimeServe` (a shaken serve component actually answers a request), both in `WasmLispCompilerIntegrationTest`; `FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective` for the renderer half.

## JVM

The counterpart post-pass is `am.ik.jvm.JvmClassShaker`, run at the end of `JvmLispCompiler.compile`. It parses the finished class, builds the call graph from the `invoke*` constant-pool immediates, keeps methods reachable from `main` (plus `_apply` as an extra root when the program uses `java:` interop — the embedded bridge looks `_apply` up REFLECTIVELY, an edge bytecode cannot show), drops unreachable methods and any static field only they referenced, and **compacts the constant pool**, rewriting every CP index immediate in the surviving bytecode in place (sizes never change: u2 stays u2, an `ldc` u1 index only shrinks because compaction preserves order — so exception-table pcs and switch padding stay valid; no method renumbering is needed since JVM methods are referenced by name). Dispatch methods keep eval/funcall/`#'` targets alive exactly as on WASM. The shaker throws on anything it does not recognize (unknown opcode/constant tag, any attribute other than a single `Code` per method) rather than emit a corrupt class; `fact` drops ~46 KB -> ~4.6 KB.

Tests: `JvmClassShakerTest` (structural + behavior, incl. the `_apply` root) and `JvmClassShakerCorpusTest` (compiles the whole `ci-spec.yaml` corpus with `--optimize`, asserts shrink + identical run output — the decoder-completeness guard, like `WasmTreeShakerCorpusTest`). Limitations (README "Optimize").
