# `concatenate` — the three result families (one contract, four backends)

`compiler/ConcatenateForms` is the ONE home of the contract; user behavior lives in
`doc/{en,ja}/reference/functions/concatenate.md`.

- `resultSpec(designator, closRegistry)` normalizes an EVALUATED designator to
  `ResultSpec(family, intWidth)` -- families `STRING`, `LIST`, `VECTOR` (bit-vector
  spellings included), `intWidth` 8/16/32 for `(unsigned-byte N)` else 0.
  `literalResultSpec`/`literalResultFamily` normalize the type AS WRITTEN (only a literal
  `(quote ...)`).
- `expand(cons, normalizeArguments)` is the compile-path lowering from
  `Jvm/WasmExprCompiler`'s `CONCATENATE` case; no per-backend emission. STRING -> a nested
  binary `%string-concat` chain (a lone argument concatenates with `""`, so the result is
  always fresh); LIST -> `(append (coerce a 'list) ... nil)`, the trailing `nil` being what
  makes `append` copy the LAST argument; VECTOR -> that list in `(coerce ... 'vector)`,
  packed -> in `(%seq-int-vector ... width)`.
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

## `coerce` shares that arm; `map` does not
- `packedVectorCoerce(cons, closRegistry)`: same `literalResultSpec`, same `%seq-int-vector`,
  same gate (widened to a `coerce` designator at index 2). The three coerce sites consult it
  BEFORE `LispMacroExpander.expandCoerce`; no packed width -> null -> byte-identical output.
- Width test lives in `LispNames.unsignedByteWidth` / `packedVectorWidth` (root package)
  because `PureBuiltinFolder` asks from `macro`, which may not import `compiler`.
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
`(concatenate 'string ...)` with a non-literal-string argument; the flag rides
`Ctx.usesSeqString` (must be copied by `WasmAsyncEmit.freshCtx`). Correctness, not
optimization: `LispMacroExpander` emits `(concatenate 'string ...)` during CODEGEN long after
the scan, and wrapping those would call a helper the gate did not inject.

## First-class value, computed `coerce`, `--no-gc`
- `BuiltinFunctionWrappers.concatenateWrapper` (`REFERENCE_GATED_FUNCTIONS`, injected only on
  `(function concatenate)`) re-does family dispatch with `member` at run time, mirroring
  `expand` arm for arm; the vector arm compares `(cadr type)` with `equal` against each
  `(unsigned-byte N)` list -- no spec-shape reading.
- `LispMacroExpander.expandComputedCoerce` dispatches on the designator's head over the same
  families, each arm the SAME body the literal path emits, plus `t` as identity.
- `NoGcWasmCompiler.compileConcatenate` builds strings in linear memory, never through
  `expand`; a non-string family is a compile error (`.kb/no-gc-scalar-wasm.md`).

## Pinning
- ci-spec `concatenate-result-families`, `concatenate-packed-element-type`,
  `coerce-packed-element-type` (literal and computed side by side).
- `LispEvaluatorTest#evalConcatenate*`, `#evalSeqIntVectorHelper`,
  `#evalCoerceKeepsThePackedElementType`.
- `JvmLispCompilerTest#compileAndRunConcatenate*`,
  `#compileConcatenateWithComputedResultTypeFails`,
  `#compileAndRunCoerceKeepsThePackedElementType`.
- `WasmLispCompilerIntegrationTest#concatenate{BuildsListAndVectorResultTypes,ResolvesADeftypeAliasResultType,KeepsThePackedElementType}`,
  `#coerceKeepsThePackedElementTypeAndBakesALiteralTable`.
- `IroncladE2eTest` (HKDF vector), `LackEcosystem*E2eTest` lack legs.
