# `concatenate` — the three result families (one contract, four backends)

`compiler/ConcatenateForms` is the ONE home of the contract; user behavior lives in
`doc/{en,ja}/reference/functions/concatenate.md`.

- `resultSpec(designator, closRegistry)` normalizes an EVALUATED designator to
  `ResultSpec(family, elementType)` -- families `STRING`, `LIST`, `VECTOR` (bit-vector
  spellings included), `elementType` an `ArrayElementTypes` CODE (`T` for a general
  result). **The code, not a width**: the packed representations are a closed code space
  and a second width field beside the integer one is what left the float widths with
  nowhere to be for months (`.todo/707`, 2026-09-06). Which representation a code names is
  asked of the representations -- `ConcatenateForms.packedIntWidth` (via
  `LispNames.unsignedByteWidth`) and `isPackedFloat` (via `LispFloatArray.prototypeFor`,
  i.e. the sealed umbrella's own permits). `ResultSpec.intWidth()` is a derived reader kept
  for its callers. `literalResultSpec`/`literalResultFamily` normalize the type AS WRITTEN
  (only a literal `(quote ...)`).
- `expand(cons, normalizeArguments)` is the compile-path lowering from
  `Jvm/WasmExprCompiler`'s `CONCATENATE` case; no per-backend emission. STRING -> a nested
  binary `%string-concat` chain (a lone argument concatenates with `""`, so the result is
  always fresh); LIST -> `(append (coerce a 'list) ... nil)`, the trailing `nil` being what
  makes `append` copy the LAST argument; VECTOR -> that list through `packedVectorCall`:
  `(%seq-int-vector ... width)` for a packed integer code, `(%seq-float-vector ... code)`
  for a packed float one, `(%seq-string ...)` for CHARACTER, `(coerce ... 'vector)` for
  everything else.
- The interpreter keeps its Java builtin over the same `resultSpec` and therefore also
  accepts a COMPUTED result type -- the one deliberate interpreter-only extra.

## Packed `(unsigned-byte 8|16|32)` vector results
ANSI requires the result to BE the requested type, so these build the PACKED representation
(`.kb/packed-integer-vectors.md`).

- `%seq-int-vector` (`LispNames.SEQ_INT_VECTOR`, `cl` internal, a `BuiltinFunctionWrappers`
  entry): `(coerce seq 'list)`, one of three LITERAL
  `(make-array (length l) :element-type '(unsigned-byte N))` allocations, then a `do` loop of
  `%aset`. The element type must be LITERAL for each backend's packed recognizer, hence three
  allocations. A CALL, not inline (`.kb/wasm-function-body-size.md`).
- Gate: `needsSeqIntVector(program)` OR a `#'concatenate` reference. The same flag forces the
  JVM's `usesIntArray` gate on; wasm-GC needs no forcing.
- **Trap: element type is a SHAPE rule, not a position rule** -- `(vector T ...)` leads with
  it while `(simple-vector SIZE)` carries a SIZE, so reading position 1 unconditionally makes
  `(simple-vector 41)` a specialized request. Unsupported widths stay general vectors.

## Packed FLOAT vector results
`single-float` / `double-float` / `bfloat16` are members of the same code space, so the same
designator rule builds a packed float array (`.kb/vec.md`) -- landed 2026-09-06 (`.todo/707`);
before it, both operators answered a GENERAL vector from a list source and the ARGUMENT
UNCHANGED from an already-packed one, at every float width.

- `%seq-float-vector` (`LispNames.SEQ_FLOAT_VECTOR`, `cl` internal, a
  `BuiltinFunctionWrappers` entry): `%seq-int-vector`'s shape with the ArrayElementTypes
  CODE in place of the width -- `(coerce seq 'list)`, one LITERAL `(make-array (length l)
  :element-type '<name>)` per packed float code, then the same `do` loop of `%aset`. The
  arms are DERIVED (`packedFloatElementTypeCodes()` filters `specializedCodes()` through
  `ConcatenateForms.isPackedFloat`), so a fourth width is reachable here as soon as it is
  reachable from `make-array`.
- **Two helpers, not one over the whole code space**, so each rides its own gate
  (`needsSeqFloatVector` beside `needsSeqIntVector`) and a program asking for one packed
  family carries none of the other's allocations. On the JVM the float gate forces
  `usesFloatArray` exactly as the integer one forces `usesIntArray`.
- **bfloat16 is interpreter + JVM only** and the refusal reaches THIS path because the
  representation is chosen at the helper's `make-array`: on wasm-GC that arm compiles to
  `WasmArrayCompiler`'s call-time signal, so a program that never names the width is
  unaffected and one that does gets `UnsupportedFloatWidth`'s sentence at the call, the
  same one a literal `(make-array :element-type 'bfloat16)` gets (`.kb/bfloat16.md`).
  `--no-gc` has no `coerce` operator and refuses a non-string `concatenate` family, so it
  never reaches either helper.
- No fold: `PureBuiltinFolder` bakes a literal `(coerce '(...) '(vector (unsigned-byte N)))`
  into a packed literal and has no float twin -- a float table builds through the helper at
  run time on every backend.

## CHARACTER vector results build a STRING
Until 2026-09-06 (`.todo/714`) `character` was the one specialized code with no packed
vector arm: a `(vector character)` result stayed a GENERAL vector, so `array-element-type`
answered `t`, `typep` against `(simple-array character (*))` was false, and the value did
not print or `stringp` as a string -- SBCL answers `character` / `stringp` T / `"ab"` for
the same calls, because a `(vector character)` result IS a string in CL.

- The decided shape is what `make-array`'s `:element-type 'character` already builds: a
  mutable `LispString`, not an immutable value borrowed from the `'string` family. That
  keeps the designator meaning ONE thing everywhere it names `character` -- `make-array`,
  `coerce` and `concatenate` all answer the same representation for it.
- `packedVectorCall`'s CHARACTER arm is `(%seq-string elements)` -- the STRING family's own
  element-to-string builder, reused rather than re-derived, so `(coerce seq '(vector
  character))` and `(concatenate '(vector character) seq)` build the identical value from
  the identical elements.
- `Environment.concatenateBuiltin` -- the interpreter's own `concatenate`, which does NOT go
  through `ConcatenateForms.expand`/`packedVectorCall` at all (it re-derives the packed int
  and float arms directly over the same `resultSpec`) -- needed its own CHARACTER arm too:
  `charVector(fn, elements)`, the shared tail also used by the interpreter's `%seq-string`.
- Gate: folded into `needsSeqString(program)` -- a `concatenate` / `coerce` whose result
  type asks for `(vector character)` always needs `%seq-string` (unlike the STRING family's
  own gate, which only fires for a non-literal-string argument), via the same
  `needsPackedVector` scan `needsSeqIntVector` / `needsSeqFloatVector` use.
- `BuiltinFunctionWrappers.concatenateWrapper`'s vector arm gained a CHARACTER test
  (`member` over `ArrayElementTypes.CHARACTER_SPELLINGS`) ahead of the float/general
  fallback, calling `%seq-string` the same way -- so a `#'concatenate` reference now forces
  `usesSeqString` on too, exactly as it already forced `usesSeqIntVector` /
  `usesSeqFloatVector`.

## `coerce` shares those arms; `map` does not
- `packedVectorCoerce(cons, closRegistry)`: same `literalResultSpec`, same helpers, same
  gates (widened to a `coerce` designator at index 2). The three coerce sites consult it
  BEFORE `LispMacroExpander.expandCoerce`; no packed element type -> null -> byte-identical
  output.
- Width test lives in `LispNames.unsignedByteWidth` / `packedVectorWidth` (root package)
  because `PureBuiltinFolder` asks from `macro`, which may not import `compiler`;
  `packedVectorElementType` is the SHAPE rule both of them and the float side read.
- **`map` still drops it** (`expandMap` collapses a compound vector designator to bare
  `'VECTOR`, and that collapse is what keeps the gate sound), and **a COMPUTED coerce
  designator is still general**. Either fix must stop the collapse, route through
  `packedVectorCoerce`, and widen `needsSeqIntVector` in the SAME pass, or the helper is
  missing at run time.

## A user deftype alias resolves through the class registry
`resultFamily(designator, closRegistry)` resolves a non-built-in designator through
`ClosRegistry.findDeftype`, transitively and depth-capped (fast-http's
`'simple-byte-vector`); registry-carrying entry points are `literalResultFamily`, `expand`,
`needsSeqString`. **Ordering**: the WASM compiler runs that scan AFTER
`expandTopLevelDefinitions` so the registry is populated. The interpreter builtin is
re-registered with the evaluator's registry (`Environment.concatenateBuiltin`); the
`#'concatenate` wrapper is deliberately NOT alias-aware (no registry at run time).

## String family takes any character sequence (`%seq-string`)
`(concatenate 'string "a" '(#\b #\c) #(#\d) nil "e")` = `"abcde"` on every backend; a
non-character element is an error, not a silent `princ`. `%seq-string`
(`LispNames.SEQ_STRING`, `cl` internal) is `(lambda (x) (if (stringp x) x (coerce x 'string)))`
-- the loop emitted once, inside it.

Gate: `needsSeqString(program)` -- true when the PROGRAM ITSELF writes a
`(concatenate 'string ...)` with a non-literal-string argument, OR a `concatenate` /
`coerce` whose result type asks for `(vector character)` (which always needs the helper,
literal arguments included -- see "CHARACTER vector results build a STRING" above); the
flag rides `Ctx.usesSeqString` (which `WasmAsyncEmit.freshCtx` inherits). Correctness, not
optimization: `LispMacroExpander` emits `(concatenate 'string ...)` during CODEGEN long after
the scan, and wrapping those would call a helper the gate did not inject.

## First-class value, computed `coerce`, `--no-gc`
- `BuiltinFunctionWrappers.concatenateWrapper` (`REFERENCE_GATED_FUNCTIONS`, injected only on
  `(function concatenate)`) re-does family dispatch with `member` at run time, mirroring
  `expand` arm for arm; the vector arm compares `(cadr type)` with `equal` against each
  `(unsigned-byte N)` list and each packed float NAME, and with `member` against
  `ArrayElementTypes.CHARACTER_SPELLINGS` for CHARACTER -- no spec-shape reading, and the
  float names come from the same `packedFloatElementTypeCodes()` the helper's arms do. A
  `#'concatenate` reference therefore gates all THREE helpers in
  (`usesSeqIntVector` / `usesSeqFloatVector` / `usesSeqString`).
- **The wrapper mirrors `expand`'s VALUES, not its shape, and must not FOLD** -- it is the
  arm `apply` reaches with a runtime argument list, so its argument count is the DATA's and
  not the program text's. Its string arm sizes the result once (`mapcar` normalize, a
  `reduce` for the total, `make-string`, a second `reduce` carrying the write offset into
  `replace`) and its list arm folds from the RIGHT (`:from-end t`); both used to fold left
  through `%string-concat` / `append`, which cost the sum of the prefixes
  (`.kb/string-accumulate-cost.md`). The call-position `stringChain` above keeps its binary
  chain on purpose, because there the argument count is written in the source.
- `LispMacroExpander.expandComputedCoerce` dispatches on the designator's head over the same
  families, each arm the SAME body the literal path emits, plus `t` as identity.
- `NoGcWasmCompiler.compileConcatenate` builds strings in linear memory, never through
  `expand`; a non-string family is a compile error (`.kb/no-gc-scalar-wasm.md`).

## Pinning
- ci-spec `concatenate-result-families`, `concatenate-packed-element-type`,
  `coerce-packed-element-type` (literal and computed side by side),
  `coerce-packed-float-element-type` (bfloat16 deliberately absent -- it is pinned per
  backend instead), `concatenate-coerce-vector-character-builds-a-string`.
- `LispEvaluatorTest#evalConcatenate*`, `#evalSeqIntVectorHelper`, `#evalSeqFloatVectorHelper`,
  `#evalCoerceKeepsThePackedElementType`,
  `#evalCoerceAndConcatenateKeepThePackedFloatElementType`,
  `#evalConcatenateAndCoerceToVectorCharacterBuildAString`.
- `JvmLispCompilerTest#compileAndRunConcatenate*`,
  `#compileConcatenateWithComputedResultTypeFails`,
  `#compileAndRunCoerceKeepsThePackedElementType`,
  `#compileAndRunCoerceAndConcatenateKeepThePackedFloatElementType`,
  `#compileAndRunCoerceAndConcatenateToVectorCharacterBuildAString`.
- `WasmLispCompilerIntegrationTest#concatenate{BuildsListAndVectorResultTypes,ResolvesADeftypeAliasResultType,KeepsThePackedElementType}`,
  `#coerceKeepsThePackedElementTypeAndBakesALiteralTable`,
  `#coerceAndConcatenateKeepThePackedFloatElementType` (carries the bfloat16 refusal text),
  `#coerceAndConcatenateToVectorCharacterBuildAString`.
- `eval/PackedFloatReachabilityTest#everyPermitIsReachableThroughCoerceAndConcatenate`,
  `#everySpecializedElementTypeCodeSurvivesCoerceAndConcatenate` (CHARACTER included, with
  a character literal in place of the integer every other width accepts) and
  `#theSpecializedCodeSpaceNamesExactlyThePackedFloatPermits` -- the third is what makes the
  second a pin rather than a shrinking loop.
- `IroncladE2eTest` (HKDF vector), `LackEcosystem*E2eTest` lack legs.
