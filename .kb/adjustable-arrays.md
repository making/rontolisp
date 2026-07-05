# Fill-pointer / adjustable / displaced arrays (`copy-array` surface)

Split out of `.todo/71-adjustable-arrays-copy-array.md` (itself split from the
cl-utilities residue `.todo/65`). This is the extended-array surface that
cl-utilities `copy-array` touches:

- `make-array` options `:fill-pointer`, `:adjustable` (and, later,
  `:displaced-to` / `:displaced-index-offset`).
- `fill-pointer` (+ `setf`), `array-has-fill-pointer-p`, `adjustable-array-p`,
  `array-element-type`.
- `vector-push` / `vector-pop` / `vector-push-extend`.
- (future) `adjust-array`, `array-displacement`, `copy-array` itself.

## Status (2026-07-06)

**Interpreter: DONE and tested.** All of the above except displacement /
`adjust-array` are implemented on the interpreter, and the verbatim cl-utilities
`copy-array` definition runs there (pinned by
`LispEvaluatorTest.clUtilitiesCopyArrayRunsOnInterpreter`, the headline
acceptance of todo 71).

**JVM / WASM / `--no-gc`: NOT YET** -- these are the heavy per-backend pieces,
split into follow-up todos (`.todo/72` JVM, `.todo/73` WASM). The names are
already registered as `cl` functions (`PackageRegistry`), so a compiled program
that calls e.g. `vector-push` currently fails at compile time (no codegen case),
not silently. Adding them to `CL_SYMBOLS` bumped the `list-functions` count
`229 -> 236` (pinned in `LispEvaluatorTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`).

## Representation

### Interpreter (`LispArray`) -- implemented

`LispArray` gained mutable state (fields are no longer `final`):

- `int fillPointer` -- the fill pointer, or `-1` for none. When present it is
  the effective length; only rank-1 arrays may have one.
- `boolean adjustable` -- the `:adjustable` flag (verbatim; reported by
  `adjustable-array-p`).

`data` / `dimensions` are non-final so `vector-push-extend` can reallocate. Key
methods: `effectiveLength()` (fill pointer if present, else `data.length`),
`vectorPush` (returns the index or `-1` when full), `vectorPop`,
`vectorPushExtend` (grows `data` + `dimensions[0]` when full), `setFillPointer`.
`render()` (the `#(...)` printer) iterates `effectiveLength()`, so a
fill-pointer vector prints only up to the fill pointer. `length` (in
`Environment`) uses `effectiveLength()`.

`aref` / `row-major-aref` still reach the FULL backing store (CL semantics: the
fill pointer bounds the sequence view, not element access).

`make-array` parses `:fill-pointer` (`t` = size, integer = that value, `nil` =
none; rank-1 only), `:adjustable`, and ignores unknown keywords such as
`:element-type`. `array-element-type` always returns `t` (element types are not
tracked).

New builtins live in `Environment.registerArrays`: `fill-pointer`,
`%set-fill-pointer` (the `setf` target, wired in
`LispMacroExpander.expandSetf`), `array-has-fill-pointer-p`,
`adjustable-array-p`, `array-element-type`, `vector-push`, `vector-pop`,
`vector-push-extend`.

### JVM (planned) -- `.todo/72`

Today a JVM array is a `java.util.ArrayList`: slot 0 = an `Object[]` of `Long`
dims, slots `1..` = row-major data (`JvmArrayRuntimeBuilder`). A fill pointer /
adjustable flag has to be carried WITHOUT shifting the data offset (which
`_aref1`/`row-major-aref` and the `1 + flat` index all bake in) and without
breaking rank detection (`rank == header.length` in `_arrayDims`, `emitFlatN`,
`buildToString`, `JvmQuoteCompiler.compileQuotedArray`).

Recommended design: **wrap slot 0** in a fixed 3-element header
`Object[]{ dimsInner (Object[] of Long), fillPointer (Long|null), adjustable
(Boolean|null) }`. Data stays at slots `1..` (so `_aref*`/`_aset*` and the
`row-major` reuse are untouched); only the "read dims" accessor gains one extra
`aaload` in `emitFlat2`/`emitFlatN`/`_arrayDims`/`buildToString`, and BOTH
producers (`_arrayMake` and `JvmQuoteCompiler`) build the wrapper. `_arrayMake`'s
signature grows to `(dims, init, fillPointer, adjustable)`. `buildToString` and
`JvmLengthRuntimeBuilder` clamp the element count to the fill pointer. New static
helpers: `_fillPointer`, `_setFillPointer`, `_arrayHasFillPointer`,
`_adjustableArrayP`, `_vectorPush`, `_vectorPop`, `_vectorPushExtend`
(push-extend grows the `ArrayList` + the inner dims). Also needs a
`BuiltinFunctionWrappers` entry per name for `#'vector-push` etc., and the
`--optimize` shaker must keep the helpers reachable when used.

### WASM (planned) -- `.todo/73`

Today a WASM array is a `TYPE_CELL` box holding a header `TYPE_CONS`
`(dims . data)`, both `TYPE_HASH_BUCKETS` arrays of `(ref null eq)`
(`WasmArrayCompiler`); dims holds i31 sizes, data the row-major elements. Rank =
`dims.length`. Carry the fill pointer / adjustable flag by extending the header
(e.g. the box holds `(meta . (dims . data))` where meta is a 2-slot buckets of
`[fillPointer-i31-or-null, adjustable-i31]`), or a parallel small buckets. All
inline emission (`compileMake`/`compileAref`/`compileAset`/`compileDims`) plus
`WasmLengthCompiler` and the array printer must follow the new indirection. Keep
the static `FUNC_*` import indices unchanged (inline-only, no new heap type) so
the component blobs stay valid. `--component` I/O adapter is unaffected (arrays
are pure in-memory values).

### `--no-gc` scalar WASM (planned) -- `.todo/73` or its own todo

`ScalarWasmCompiler` has no general array type today (only string/char over
`[len][bytes]` headers). Fill-pointer vectors here are a sharp edge; gate with a
clear compile error if impractical, per the todo-71 acceptance ("displacement
documented even if limited/unsupported on `--no-gc`").

## Displacement / `adjust-array` (future)

`:displaced-to` (+ `:displaced-index-offset`), `array-displacement` and
`adjust-array` are the hardest part (aliasing semantics -- a displaced array
shares another array's storage). Deferred entirely; see the todo. `copy-array`
does not need displacement, so it is not on the critical path.

## Tests

- Interpreter: `LispEvaluatorTest` -- `fillPointerLengthAndAccessors`,
  `fillPointerVectorPrintsUpToFillPointer`, `vectorPushStoresAndReturnsIndexOrNil`,
  `vectorPushThenReadBack`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`,
  `setfFillPointer`, `simpleVectorHasNoFillPointer`,
  `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`.
- The `list-functions` count (236) is pinned in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`.
