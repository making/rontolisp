# The literal sequence conversions are shared callees, not per-site code

**Invariant: no literal `(coerce x 'list/'string/'vector)` site -- including the
string/vector dispatch every generic sequence lowering wraps its scan in -- emits the
conversion body inline when the program carries the `%seq-to-list` / `%seq-to-string` /
`%seq-to-vector` trio. The program carries each conversion at most once.**

Precedent: [[subseq-runtime]] (its mechanics first; this file is the deltas),
[[string-write-runtime]], [[wasm-shared-coercion]], [[format]]'s `%fixed-decimal`.

## Mechanism
- Two `LispMacroExpander` builders reach a conversion: `seqResultDispatchForm` (`reverse`,
  `remove`/`substitute`/`remove-duplicates` families, the `sort`/`stable-sort` string wrap) and
  `seqAsListForm` (`position`/`find`, `count`, `every`/`some`/`notany`/`notevery`, `reduce`'s
  keyword forms). Inlined, a conversion is 8-10 KB of wasm-GC PER SITE.
- `LispMacroExpander.seqConversionWrappers()` answers the three definitions, bodies verbatim
  from `coerceToListBody(x, true)` / `coerceToStringBody(x, true)` / `coerceToVectorBody(x)`.
  **Trap: none may contain a literal `coerce` form** -- that re-enters the routing forever.
- ONE routing point: the compilers' `coerce` case calls `expandCoerce(cons, arraysExist,
  helpersPresent)` with `helpersPresent = ctx.functions.containsKey(%seq-to-list)`;
  `expandComputedCoerce` routes each arm the same way. Without the trio the inline lowering
  returns, so an under-predicting gate costs sharing, never correctness -- which is why
  `expandMap`'s `'vector` path returns a coerce FORM instead of pre-expanding it.
- **Injection is the BACKEND's**, right after `BuiltinFunctionWrappers.generate`, gated by
  `LispMacroExpander.programUsesSeqConversion` (`SEQ_CONVERSION_USERS`; `nreverse` deliberately
  off it). The trio always travels together.
- **The backends gate differently** (as `%subseq-runtime`): wasm on the name scan alone, the JVM
  additionally on `programUsesAnyArrayOp` since the vector arms name `aref`/`%aset`/`make-array`.
- The interpreter never routes -- `LispEvaluator`'s `coerce` case calls the one-argument
  `expandCoerce`; its own half is [[seq-coerce-runtime]] (Java, for SPEED).
- A site keeps its own scan loop with `:test`/`:key` inlined; marginal cost 66-852 bytes,
  whole modules dropped 9-51%.
- Unfinished: `%seq-to-list` still walks `map`'s `nth`-based index loop (quadratic on long
  lists). Fix inside the helper or with a `listp` fast path at the SITE, never by re-inlining.

## The computed-coerce fall-through is a `typep`, coupling two gates
A computed result type's leftover arm is `(if (typep x spec) x (error ...))`, a call to the
shared `%typep-runtime` defun that `expandTopLevelDefinitions` injects only when
`LispMacroExpander.needsRuntimeTypep` says so -- and that scan sees the SOURCE program, never
the injected wrappers. Three things must stay in step:
- the scan counts a computed `coerce` beside a computed `typep`, and `(function coerce)` beside
  `(function typep)`, including inside QUOTED data (`BuiltinFunctionWrappers.referencesFunctionValue`);
- `#'coerce` is in `REFERENCE_GATED_FUNCTIONS`, injected only for a program naming it;
- the `#'map` wrapper dispatches onto three LITERAL coerces, riding the EVAL runtime's gate, so
  no reference scan could see it coming.

## Tests
`LispMacroExpanderTest.aCoerceSiteIsOneCallWhenTheProgramCarriesTheSharedConversions`,
`.theSharedConversionsAnswerTheSameThingAsTheInlinedOnes`,
`.theSeqConversionGateNamesTheOperatorsThatCanReachAConversion`;
`WasmLispCompilerTest.aSequenceOperatorSiteDoesNotCarryItsOwnCopyOfTheSharedConversions` (byte
budgets). Behavior stays pinned by the sequence cases in `ci-spec.yaml`, `LispEvaluatorTest`,
`JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, `ExamplesE2eTest`.
