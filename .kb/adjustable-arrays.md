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

**Fill-pointer sub-step: DONE on all four backends** (interpreter, JVM, WASM
Preview 1, WASM component) and tested; the verbatim cl-utilities `copy-array`
definition runs everywhere (pinned by
`LispEvaluatorTest.clUtilitiesCopyArrayRunsOnInterpreter`,
`JvmLispCompilerTest.compileClUtilitiesCopyArray`,
`WasmLispCompilerIntegrationTest.compileClUtilitiesCopyArray`, and the
`fill-pointer-arrays-cross-backend` ci-spec case). `--no-gc` rejects the whole
surface with its usual clear compile error (arrays are ineligible on the scalar
backend: `--no-gc: unsupported operation 'vector-push' ...`), satisfying the
todo-71 "gate explicitly" acceptance without a dedicated gate.

Semantics shared by all backends: only rank-1 arrays may have a fill pointer
(`:fill-pointer t` = the vector size, an integer = that value, range-checked);
the fill pointer is the effective length (`length`, `#(...)` printing, the
sequence view) while `aref`/`row-major-aref` still reach the full backing
store; `vector-push-extend` grows any fill-pointer vector regardless of
`:adjustable` (the flag is reported verbatim by `adjustable-array-p`);
`array-element-type` always returns `t` (`:element-type` is parsed and
ignored). `%set-fill-pointer` is the internal `setf` target wired in
`LispMacroExpander.expandSetf`. On the compile path `array-element-type`
expands to `(progn <array> t)` (`LispMacroExpander.expandArrayElementType`).

First-class values: `#'vector-push` etc. work via
`BuiltinFunctionWrappers.ARRAY_FILL_POINTER_FUNCTIONS` (fill-pointer,
array-has-fill-pointer-p, adjustable-array-p, array-element-type, vector-push,
vector-pop, vector-push-extend -- the last in its 2-arg form), gated like
`HASH_FUNCTIONS`: both compilers inject the group only when
`programUsesAnyArrayOp` (which also gates the JVM array helpers and now lists
the fill-pointer names) is true, so the wrappers and their helpers stay gated
together.

Interpreter/JVM errors carry `fn: message` text (e.g. "vector-pop: empty
vector"); WASM traps (`unreachable`) on the same conditions. Compiled `list`
argument evaluation is right-to-left (.todo/14), so tests/ci-spec sequence
side-effecting pushes/pops through separate bindings.

## Representation

### Interpreter (`LispArray`)

`LispArray` has mutable state (fields are no longer `final`):

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
`:element-type`. New builtins live in `Environment.registerArrays`.

### JVM (implemented)

An array is still a `java.util.ArrayList`, but slot 0 now holds a fixed
3-element header `Object[]{dims, fillPointer, adjustable}` -- `dims` the
`Object[]` of Long dimension sizes (length = rank), `fillPointer` a `Long` or
null, `adjustable` the RAW `:adjustable` argument (null = nil; kept verbatim so
`_adjustableArrayP` is a null test). Data stays at slots `1..` (the `1 + flat`
offset in `_aref*`/`_aset*` is untouched); every dims reader gained one extra
`aaload 0` (`emitFlat2`, `emitFlatN`, `_arrayDims`, `buildToString`,
`JvmLengthRuntimeBuilder`). BOTH header producers build the wrapper:
`_arrayMake` (signature grew to `(dims, init, fillPointer, adjustable)`;
`JvmArrayCompiler.compileMake` compiles the keyword value expressions or pushes
null) and `JvmQuoteCompiler.compileQuotedArray` (literals: slots 1/2 null).
`buildToString` + `_length` clamp the element count to the fill pointer.

New static helpers in `JvmArrayRuntimeBuilder` (same array gate):
`_fillPointer`, `_setFillPointer`, `_arrayHasFillPointer`, `_adjustableArrayP`,
`_vectorPush`, `_vectorPop`, `_vectorPushExtend` (push-extend appends nulls to
the ArrayList and updates the inner dims[0]). Call sites are wired in
`JvmArrayCompiler` + `JvmExprCompiler.compileCons`; `--optimize`
(`JvmClassShaker`) keeps used helpers via the ordinary invokestatic call graph
(verified with `--optimize` on a fill-pointer program).

### WASM (implemented)

The `TYPE_CELL` box now holds a header `TYPE_CONS` of
`(dims . (meta . data))`: `dims`/`data` are the same `TYPE_HASH_BUCKETS`
arrays; `meta` is a `TYPE_CONS` of `(fillPointer-i31-or-null . adjustableRaw)`.
The header's CAR is still the dims buckets array, so the array-vs-hash-table
discriminator used by `%arrayp` / `WasmLengthCompiler` / the printer is
unchanged, and `compileDims`/`emitFlatIndex` (car readers) needed no change;
data readers go one cons deeper (`WasmArrayCompiler.getData` =
`cdr`/`cdr`/cast-buckets, meta = `cadr`). Producers: `compileMake` (resolves
`:fill-pointer` at runtime -- null / bounds-checked i31 / `t` -> dims[0] --
only when the keyword appears at the call site) and
`WasmQuoteCompiler.compileQuotedArray` (meta `(null . null)`).
`WasmLengthCompiler` and the shared printer branch
(`WasmRuntimeBuilder.emitPrintArray`) clamp to `meta.car` when it is an i31.
The new builtins are emitted INLINE in `WasmArrayCompiler`
(compileFillPointer/SetFillPointer/HasFillPointer/AdjustableArrayP/VectorPush/
VectorPop/VectorPushExtend; push-extend copies into a fresh buckets array with
a loop and `struct.set`s the inner cons + dims[0]) -- no new heap type, no new
`FUNC_*` index, so the component blobs are untouched and Preview 1 /
`--component` stay identical.

### `--no-gc` scalar WASM

Unsupported, like every array operation on the scalar backend: the eligibility
scan (`ScalarWasmCompiler.collectCallsCons`) names the operation in a clear
compile error ("--no-gc: unsupported operation 'vector-push' in function 'f'
..."). Documented under the `--no-gc` section of `doc/*/compiling/wasm.md`
("vectors" in the ineligible list).

## Displacement / `adjust-array` (future)

`:displaced-to` (+ `:displaced-index-offset`), `array-displacement` and
`adjust-array` are the hardest part (aliasing semantics -- a displaced array
shares another array's storage). Deferred entirely; see `.todo/71`. `copy-array`
does not need displacement, so it is not on the critical path.

## Tests / docs

- Interpreter: `LispEvaluatorTest` -- `fillPointerLengthAndAccessors`,
  `fillPointerVectorPrintsUpToFillPointer`, `vectorPushStoresAndReturnsIndexOrNil`,
  `vectorPushThenReadBack`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`,
  `setfFillPointer`, `simpleVectorHasNoFillPointer`,
  `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`.
- JVM: `JvmLispCompilerTest.compileFillPointer*` / `compileVectorP*` /
  `compileSetfFillPointer` / `compileSimpleVectorHasNoFillPointer` /
  `compileFillPointerFirstClassWrappers` / `compileClUtilitiesCopyArray`.
- WASM: the same set in `WasmLispCompilerIntegrationTest`.
- E2E: ci-spec `fill-pointer-arrays-cross-backend` (all four backends).
- Docs: `reference/functions/{fill-pointer,array-has-fill-pointer-p,
  adjustable-array-p,array-element-type,vector-push,vector-pop,
  vector-push-extend}.md` (en+ja) + the make-array page + the functions table.
- The `list-functions` count (236) is pinned in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`.
