# The literal sequence conversions are shared callees, not per-site code

**Invariant: no literal `(coerce x 'list/'string/'vector)` site — including the string/vector dispatch every generic sequence lowering wraps its scan in — emits the conversion body inline when the program carries the `%seq-to-list` / `%seq-to-string` / `%seq-to-vector` trio. The program carries each conversion at most once.**

Precedent: `.kb/subseq-runtime.md` (read its mechanics first; this file records the deltas), `.kb/string-write-runtime.md`, `.kb/wasm-shared-coercion.md`, `.kb/format.md`'s `%fixed-decimal`.

- Two `LispMacroExpander` builders carry every generic sequence operator's representation handling: `seqResultDispatchForm` (`reverse`, `remove`/`-if`/`-if-not`, `substitute`/`-if`/`-if-not`, `remove-duplicates`/`delete-duplicates`, the `sort`/`stable-sort` string wrap) and `seqAsListForm` (`position`/`find` family, `count`/`count-if`, `every`/`some`/`notany`/`notevery`, `reduce`'s keyword forms). Inlined, a conversion is 8-10 KB of wasm-GC PER SITE.
- `LispMacroExpander.seqConversionWrappers()` answers the three wrapper-shaped definitions, each body the inline lowering verbatim (`coerceToListBody(x, true)` / `coerceToStringBody(x, true)` / `coerceToVectorBody(x)`). **Trap: none may contain a literal `coerce` form** — that re-enters the routing forever.
- ONE routing point: the compilers' `coerce` case (`JvmExprCompiler` / `WasmExprCompiler`) calls `expandCoerce(cons, arraysExist, helpersPresent)` with `helpersPresent = ctx.functions.containsKey(%seq-to-list)`; `expandComputedCoerce` routes each arm the same way. Without the trio the inline lowering returns, so an under-predicting gate costs sharing, never correctness. This is why `expandMap`'s `'vector` path returns a coerce FORM instead of pre-expanding it.
- **Injection is the BACKEND's**, right after `BuiltinFunctionWrappers.generate` (`JvmLispCompiler` / `WasmLispCompiler`), gated by `LispMacroExpander.programUsesSeqConversion` over the program and generated wrappers (`SEQ_CONVERSION_USERS`). `nreverse` is deliberately off that list. The trio always travels together.
- **The backends gate differently** (same asymmetry as `%subseq-runtime`): wasm on the name scan alone; the JVM additionally requires `programUsesAnyArrayOp`, since the vector arms name `aref`/`%aset`/`make-array`. With the JVM gate off, the coerce case inlines the vector-arm-free dispatch, so a string-only JVM program keeps the inline form and nothing calls a missing trio.
- The interpreter never routes: `LispEvaluator`'s `coerce` case calls the one-argument `expandCoerce`. Its own half is [[seq-coerce-runtime]] — conversion in Java for SPEED, where this file is one copy for SIZE.
- A site keeps its own scan loop with `:test`/`:key` still inlined; marginal cost fell to 66-852 bytes and whole modules dropped 9-51%.
- **Re-evaluation**: `%seq-to-list` still walks `map`'s `nth`-based index loop (quadratic on long lists). Fix inside the helper or with a `listp` fast path at the SITE — never by re-inlining.

## The computed-coerce fall-through is a `typep`, and that couples two gates
A COMPUTED result type's leftover arm applies CLHS's "if the object is already of the specified type, it is returned" as `(if (typep x spec) x (error ...))`, i.e. a call to the shared `%typep-runtime` defun, which `expandTopLevelDefinitions` injects only when `LispMacroExpander.needsRuntimeTypep` says so — and that scan sees the SOURCE program, never the injected wrappers. Three things must stay in step:
- the scan counts a computed `coerce` beside a computed `typep`, and `(function coerce)` beside `(function typep)`, including inside QUOTED data (`BuiltinFunctionWrappers.referencesFunctionValue` walks in there);
- `#'coerce` is in `REFERENCE_GATED_FUNCTIONS`, so its wrapper is injected only for a program naming it (the `#'typep` precedent);
- the `#'map` wrapper dispatches onto three LITERAL coerces instead of a computed one, riding the EVAL runtime's gate, so no reference scan could see it coming.

## Tests
`LispMacroExpanderTest.aCoerceSiteIsOneCallWhenTheProgramCarriesTheSharedConversions`, `.theSharedConversionsAnswerTheSameThingAsTheInlinedOnes`, `.theSeqConversionGateNamesTheOperatorsThatCanReachAConversion`; `WasmLispCompilerTest.aSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedConversions` (byte budgets — nothing else notices, every arrangement runs correctly). Behavior stays pinned by the sequence cases in `ci-spec.yaml`, `LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, `ExamplesE2eTest`.
