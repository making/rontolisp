# `concatenate` — the three result families (one contract, four backends)

User behavior: `doc/{en,ja}/reference/functions/concatenate.md`.

## `compiler/ConcatenateForms` — the ONE home of the contract

- `resultSpec(designator, closRegistry)`: normalizes an EVALUATED designator (quote stripped)
  to `ResultSpec(family, intWidth)`. Symbol or compound head ->
  `STRING` (`string`/`simple-string`/`base-string`/`simple-base-string`), `LIST`
  (`list`/`cons`), `VECTOR` (`vector`/`simple-vector`/`array`/`simple-array`/`bit-vector`/
  `simple-bit-vector`); qualified spellings normalized through their member name.
  `resultFamily` = family-only view. `intWidth` = 8/16/32 for
  `(unsigned-byte 8|16|32)`, else 0.
- `literalResultSpec` / `literalResultFamily`: normalize the type argument AS WRITTEN (only a
  literal `(quote ...)`); `expand`'s entry and `--no-gc`'s check.
- `expand(cons, normalizeArguments)`: compile-path lowering from `Jvm/WasmExprCompiler`'s
  `CONCATENATE` case. STRING -> nested binary `%string-concat` chain (a lone argument
  concatenates with `""`, so the result is always fresh), non-literal-string arguments wrapped
  in `(%seq-string arg)` when `normalizeArguments`. LIST ->
  `(append (coerce a 'list) ... nil)` -- the trailing `nil` is what makes `append` copy the
  LAST argument. VECTOR -> that list in `(coerce ... 'vector)`; packed -> in
  `(%seq-int-vector ... width)`. No new per-backend emission.
- Interpreter keeps its Java builtin (`Environment`, `LispNames.CONCATENATE`) over the same
  `resultSpec` and therefore also accepts a COMPUTED result type -- the one deliberate
  interpreter-only extra (a compiler must resolve the family statically; `coerce` splits the
  same way).

## Packed `(unsigned-byte 8|16|32)` vector results

`'(vector (unsigned-byte 8))` / `'(simple-array (unsigned-byte 8) (*))` must build the PACKED
representation (`.kb/packed-integer-vectors.md`); ANSI requires the result to BE of the
requested type, and a general vector misses a consumer's
`(simple-array (unsigned-byte 8) (*))` `etypecase` arm.

- `%seq-int-vector` (`LispNames.SEQ_INT_VECTOR`, `cl` internal, a `BuiltinFunctionWrappers`
  entry so no backend needed a primitive): `(coerce seq 'list)`, one of three LITERAL
  `(make-array (length l) :element-type '(unsigned-byte 8|16|32))` allocations, then a `do`
  loop of `%aset`. The element type must be LITERAL for each backend's packed recognizer,
  hence three allocations for a runtime width. A CALL, not inline
  (`.kb/wasm-function-body-size.md`), and it walks the list LINEARLY (inline
  `:initial-contents` indexes with `elt`, O(n) on a list).
- Gate: `ConcatenateForms.needsSeqIntVector(program)` OR a `#'concatenate` reference. It
  cannot be outrun by codegen-time expansions (nothing generated concatenates into a packed
  element type), so `expand` needs no "helper available" flag.
- The same flag forces the JVM's `usesIntArray` gate on -- that gate scans the PROGRAM and the
  helper's `make-array` lives in the wrapper. wasm-GC needs no forcing (packed array types
  and `_iv_set` are unconditional).
- Trap: element type is a SHAPE rule, not a position rule -- `(vector T ...)`,
  `(array T ...)`, `(simple-array T ...)` lead with it, while `(simple-vector SIZE)` and the
  bit-vector spellings carry a SIZE. Reading position 1 unconditionally makes
  `(simple-vector 41)` a specialized request (same trap as `LispMacroExpander`'s `typep`
  array arm).
- Unsupported widths (`(unsigned-byte 4)`, `(signed-byte 8)`) and non-integer element types
  stay general vectors.
- Interpreter builds the `LispIntVector` directly (never through `expand`), sharing
  `Environment.packedIntVector` with its own `%seq-int-vector`.

## `coerce` shares that arm; `map` does not

- `ConcatenateForms.packedVectorCoerce(cons, closRegistry)`: same `literalResultSpec`, same
  `%seq-int-vector`, same `needsSeqIntVector` gate (widened to a `coerce` designator at index
  2 as well as `concatenate`'s at index 1, which also keeps `usesIntArray` forced). The three
  coerce sites (`LispEvaluator`'s `COERCE` case, `Jvm`/`WasmExprCompiler`'s) consult it BEFORE
  `LispMacroExpander.expandCoerce`, unchanged and still collapsing other compound specs to
  their head. No packed width -> null -> byte-identical output.
- Width test lives in `LispNames.unsignedByteWidth` / `LispNames.packedVectorWidth` (root
  package) because `PureBuiltinFolder` asks from `macro`, which may not import `compiler`;
  `ConcatenateForms` and `Environment` delegate.
- **`map` still drops it**: `expandMap` collapses a compound vector designator to bare
  `'VECTOR` before `(coerce ... 'vector)`, so `(map '(vector (unsigned-byte 8)) #'f x)` is
  general -- and that collapse is what keeps the gate sound (no codegen-synthesized coerce can
  carry a packed designator the scan missed). Trigger: a library mapping into a byte vector;
  the fix must stop collapsing in `expandMap`, route through `packedVectorCoerce`, and widen
  `needsSeqIntVector` to see the `map` designator in the SAME pass, or the helper is missing
  at run time.
- **A COMPUTED coerce designator is still general**: `expandComputedCoerce`'s runtime vector
  arm builds a general vector, so `(coerce seq type)` with `type` = `'(vector (unsigned-byte
  8))` diverges from the literal spelling. Same trigger and gate constraint;
  `concatenateWrapper`'s runtime `equal` test is the shape to copy.

## A user deftype alias resolves through the class registry

- `resultFamily(designator, closRegistry)` resolves a designator (or compound head) naming no
  built-in member through `ClosRegistry.findDeftype`, transitively (alias-of-alias,
  depth-capped) -- e.g. fast-http's `'simple-byte-vector`.
- Registry-carrying entry points: `literalResultFamily`, `expand` (both compilers pass
  `ctx.closRegistry` at their CONCATENATE case), `needsSeqString` (a STRING-family alias must
  gate `%seq-string` in). Ordering: the WASM compiler runs that scan AFTER
  `expandTopLevelDefinitions`, the slot the JVM always used, so the registry is populated.
  Registry-less overloads remain for codegen-time expansions (`format`,
  `with-output-to-string`), which build only built-in spellings.
- Parameterized deftypes resolve because the interpreter's `foldDeftype` registers the body
  evaluated with defaulted parameters, and `UserMacroExpander` replaces the form with an
  equivalent zero-parameter deftype that `expandTopLevelDefinitions` registers.
- Interpreter builtin re-registered WITH the evaluator's registry
  (`Environment.concatenateBuiltin(closRegistry)` in the `LispEvaluator` constructor); the
  `createGlobal` default stays registry-less.
- The `#'concatenate` wrapper is deliberately NOT alias-aware (runtime `member` dispatch, no
  registry at run time). Trigger: a library `apply`-ing `#'concatenate` onto an alias
  designator would need the alias table baked in at injection time.

## String family takes any character sequence (`%seq-string`)

`(concatenate 'string "a" '(#\b #\c) #(#\d) nil "e")` = `"abcde"` on every backend; `nil` (the
empty list) is the case real code leans on. A non-character element is an error, not a silent
`princ`.

- `%seq-string` (`LispNames.SEQ_STRING`, `cl` internal, a `BuiltinFunctionWrappers` entry) is
  `(lambda (x) (if (stringp x) x (coerce x 'string)))` -- the `coerce` loop is emitted once,
  inside it. Interpreter has the equivalent Java builtin and walks elements directly. An
  inline `(coerce arg 'string)` per argument was rejected: two loops per argument at every
  site (`.kb/wasm-function-body-size.md`).
- Gate: `ConcatenateForms.needsSeqString(program)` -- true when the PROGRAM ITSELF writes a
  `(concatenate 'string ...)` with a non-literal-string argument. Flag rides
  `Ctx.usesSeqString` in both compilers (must be copied by `WasmAsyncEmit.freshCtx`, which
  also builds the synchronous top level); `expand` wraps only when set. Correctness, not
  optimization: `LispMacroExpander` emits `(concatenate 'string ...)` during CODEGEN
  (`format`, `with-output-to-string`, string-stream builders) long after the scan, and
  wrapping those would call a helper the gate did not inject.
- `#'concatenate`'s wrapper spells the contract inline
  (`(if (stringp x) x (coerce x 'string))` in its fold) rather than calling `%seq-string`, its
  injection being gated separately.

## `#'concatenate` as a first-class value

`BuiltinFunctionWrappers.concatenateWrapper`, `(lambda (type &rest seqs) ...)` in
`REFERENCE_GATED_FUNCTIONS`, injected only on `(function concatenate)`. Result type is a
RUNTIME value, so family dispatch is re-done with `member` over the designator (its `car` for
a compound spec), mirroring `expand` arm for arm: `%string-concat` fold, `(coerce x 'list)`
accumulation for list/vector, `error` otherwise (`.kb/asdf.md`). Vector arm honours the PACKED
element type: `(cadr type)` compared with `equal` against each of the three
`(unsigned-byte N)` lists, a hit calling `%seq-int-vector` -- one test per width, no
spec-shape reading (a `(simple-vector 41)` size can never be `equal` to a two-element list).
Deliberately not the alias gap above: an element type is spelled literally at the call site,
and a packed designator must not mean a general vector through `apply`.

## Computed `coerce` type

`LispMacroExpander.expandComputedCoerce` dispatches on the designator's head over the same
families, each arm the SAME body the literal path emits
(`coerceToListBody`/`coerceToVectorBody`/`coerceToStringBody`, extracted for that) plus `t` as
the identity, so a computed result type can never mean something a literal one does not.

## `--no-gc`

`NoGcWasmCompiler.compileConcatenate` builds strings in linear memory, never through `expand`;
with neither cons cells nor a general array type, a non-string family is a compile error
naming the designator (`.kb/no-gc-scalar-wasm.md`).

## Pinning

- ci-spec `concatenate-result-families` (all four backends, incl. the mixed-sequence string
  family), `concatenate-packed-element-type`, `coerce-packed-element-type` (literal and
  computed side by side).
- `LispEvaluatorTest#evalConcatenate*` incl.
  `evalConcatenateResolvesADeftypeAliasResultType` (both deftype shapes),
  `evalConcatenateKeepsThePackedElementType`,
  `evalConcatenateAliasResultTypeKeepsThePackedElementType`, `evalSeqIntVectorHelper`;
  `#evalCoerceKeepsThePackedElementType`.
- `JvmLispCompilerTest#compileAndRunConcatenate*` incl.
  `compileAndRunConcatenateWithADeftypeAliasResultType`,
  `compileAndRunConcatenateKeepsThePackedElementType`,
  `compileAndRunConcatenateAsAFunctionValueKeepsThePackedElementType`;
  `compileConcatenateWithComputedResultTypeFails`;
  `compileAndRunCoerceKeepsThePackedElementType`.
- `WasmLispCompilerIntegrationTest#concatenateBuildsListAndVectorResultTypes`,
  `#concatenateResolvesADeftypeAliasResultType`, `#concatenateKeepsThePackedElementType`,
  `#coerceKeepsThePackedElementTypeAndBakesALiteralTable`.
- `IroncladE2eTest` (HKDF vector, four backends); `LackEcosystem*E2eTest` lack legs
  (fast-http's `'simple-byte-vector`, the parameterized shape end to end).
