# Fill-pointer / adjustable / displaced arrays (`copy-array` surface)

Split out of todo-071 (itself split from the
cl-utilities residue todo-065). This is the extended-array surface that
cl-utilities `copy-array` touches:

- `make-array` options `:fill-pointer`, `:adjustable`, `:displaced-to` /
  `:displaced-index-offset`.
- `fill-pointer` (+ `setf`), `array-has-fill-pointer-p`, `adjustable-array-p`,
  `array-element-type`.
- `vector-push` / `vector-pop` / `vector-push-extend`.
- `adjust-array`, `array-displacement` (two values), `copy-array` itself.

## Status (2026-07-06)

**ALL sub-steps DONE on all four backends** (interpreter, JVM, WASM Preview 1,
WASM component): the fill-pointer surface, `adjust-array`, and `:displaced-to`
displacement + `array-displacement`. The verbatim cl-utilities `copy-array`
definition runs everywhere (pinned by
`LispEvaluatorTest.clUtilitiesCopyArrayRunsOnInterpreter`,
`JvmLispCompilerTest.compileClUtilitiesCopyArray`,
`WasmLispCompilerIntegrationTest.compileClUtilitiesCopyArray`, and the
`fill-pointer-arrays-cross-backend` ci-spec case). `--no-gc` rejects the whole
surface with its usual clear compile error (arrays are ineligible on the scalar
backend: `--no-gc: unsupported operation 'vector-push' ...`; the new names go
through the same default path), satisfying the todo-71 "gate explicitly"
acceptance without a dedicated gate.

**Fill-pointered STRINGS (mutable character vectors, ALL backends
2026-07-18)**: `make-array :element-type 'character` WITH
`:fill-pointer`/`:adjustable` builds a mutable string everywhere. On the
interpreter it is a mutable `LispString` (char[] buffer + fill pointer +
adjustable flag). On the COMPILED backends it is the GENERAL array
representation holding character elements, marked "character vector" —
JVM: a **length-4** slot-0 header `Object[]{dims, fp, adj, null}` built by
`_charVecMake` (displacement detection tightened to `header.length > 4`,
i.e. displaced ⇔ length 5, at `emitResolveDisplacement` /
`_arrayDispTarget` / `_arrayDispOffset`); WASM: the **meta offset i31 == 1**
(an ordinary array's is 0; a displaced array's data slot is a cell, so the
marker is unambiguous, and `%array-disp-offset` now reports the offset only
when the data slot IS a cell). The whole fill-pointer surface
(push/pop/push-extend, setf fill-pointer, `%array-become`, `_rmGet/_rmSet`)
runs on it unchanged, and the marker survives `adjust-array` because become
mutates the existing header in place.

String behavior comes from ON-DEMAND NORMALIZATION into the immutable
runtime string: JVM `_strv(Object)` (JvmArrayRuntimeBuilder, emitted under
the same array gate) renders the active prefix quote-framed; WASM
`_charvec_to_str` (a fixed always-emitted function right after
`FUNC_WRITE_STR_GC` — `FUNC_VEC_BASE`/`FUNC_USER_BASE` shifted by one,
reusing the unary `TYPE_CALLABLE_BASE + 0` signature, capture-aware scratch
so mid-capture normalization cannot clobber `*-to-string` output). Insert
points: the string-op compile sites (char/schar, subseq, string=/-equal,
case/trim/concat, write-string, string designator, WASM also
read-from-string / make-string-input-stream / intern / make-symbol), plus
the shared runtime bodies — JVM `emitArrayBranch` of
`_lispToString`/`_lispToDisplayString` (which also covers equal-hash-table
keys, keyed by rendered string) and `_eqv`'s equals fallback; WASM the
entries of `_equal`, `_hash`, `_print_val`, `_princ_val` plus `stringp`.
On the JVM everything is gated on `programUsesAnyArrayOp || usesFloatArray`
(`Ctx.usesArrays`), so array-free programs stay byte-identical; on WASM the
helper is always emitted (all module bytes shifted once, flag-dimension
byte-identity contracts unaffected).

Mutation flows through SHARED expansions (`LispMacroExpander`):
`expandReplace` and `expandScharSetFunctional` branch at runtime on
`(%arrayp seq)` — a vector (char vector included) is written in place via
`%row-major-aset` + `elt` and returned; an immutable string keeps the
functional rebuild (fresh string; the setf form still requires a variable
place). `lowerCharacterInitialContentsMakeArray` lowers rank-1 character
`:initial-contents` to a fresh string copy (`subseq` of a stringp contents,
else `coerce 'string`) — both compilers try it BEFORE the general
`:initial-contents` lowering. Default `:initial-element` for a char vector
is `#\Space`. Lite residue: `adjust-array` on a NON-adjustable char vector
returns a general (unmarked) vector; `#'make-array`'s variadic wrapper
(`apply` path) has no `:element-type` cue, so it builds an unmarked vector;
char reads past the fill pointer see the rendered (fp-bounded) content;
`eq`/`eql` of a char vector vs an equal-content string is content-true on
the JVM (`_eqv` normalizes) but nil on WASM (string identity = id field).
E2E: ci-spec `mutable-strings-cross-backend`; unit: the
`compileCharVector*` / `compileScharSetfMutatesCharVectorInPlace` /
`compileJzonAccumulatorPattern` sets in both compiler tests.

## adjust-array

`(adjust-array array new-dims &key initial-element fill-pointer)` on every
backend: elements are preserved at the subscripts valid in BOTH shapes
(per-subscript, not flat -- resizing a matrix keeps `(i, j)` at `(i, j)`); an
`:adjustable` array is adjusted IN PLACE and returned itself (`eq`), otherwise a
fresh array is returned; without an explicit `:fill-pointer` the old fill
pointer carries over (make-array range-checks it against the new size, so
shrinking below it errors like CL); rank mismatch and displaced inputs signal
clear errors; `:displaced-to` in adjust-array is rejected.

Implementation split, chosen to keep the n-dimensional copy logic OUT of
per-backend codegen:

- **Interpreter**: a real `Environment` built-in (`adjustArray` in
  `Environment.java`, runtime keyword parsing) over `LispArray.become(other)`
  (replaces dims/data/fillPointer in place, keeps the adjustable flag).
- **Compile path**: `LispMacroExpander.expandAdjustArray` -- a Lisp-level
  expansion over existing primitives (`make-array` with the carried-over
  `:fill-pointer`/`:adjustable`, `array-dimensions`, `array-total-size`,
  two-arg `floor` for the subscript decomposition, `row-major-aref` /
  `%row-major-aset` for the copy) plus ONE new internal primitive
  `%array-become` per backend (JVM `_arrayBecome`: header dims/fp copy +
  ArrayList resize/copy; WASM: three inline `struct.set`s swapping the header's
  dims car, meta fp and data slot). Both compilers dispatch
  `ADJUST_ARRAY -> compileExpr(expandAdjustArray(cons))`.

## Displacement (`:displaced-to`)

Lite semantics on every backend: a displaced array is a BARE VIEW -- it cannot
be combined with `:fill-pointer`/`:adjustable`/`:initial-element` (compile-time
`UnsupportedOperationException` on JVM/WASM since make-array keywords are
literal; runtime error on the interpreter), cannot itself be adjusted, and
`:displaced-index-offset` requires `:displaced-to`. The view is bounds-checked
at creation (`total + offset <= target total`, target dims product). Chains
(view of view) resolve transitively, and access follows the CURRENT storage of
each hop, so a view keeps aliasing an adjustable target after
`vector-push-extend`/`adjust-array` grow it in place -- pinned on all backends
(`displacedArraySeesTheTargetGrowInPlace` / `compileDisplacedArrays` / the
`adjust-displaced-arrays-cross-backend` ci-spec case). Rank may differ from the
target's (vector view over a matrix row).

`array-displacement` returns target + offset as TWO values via the syntactic
multiple-value tier: `isMvProducerForm`/`lowerMvProducer` recognize
`(array-displacement x)` and read the two internal accessors
`%array-disp-target` / `%array-disp-offset` over one temp; in an ordinary
context `expandArrayDisplacement` yields the primary
`(%array-disp-target x)`. No `%mv-spill` involvement.

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
vector-pop, vector-push-extend -- the last in its 2-arg form -- plus
make-array), gated like `HASH_FUNCTIONS`: both compilers inject the group only
when `programUsesAnyArrayOp` (which also gates the JVM array helpers and now
lists the fill-pointer names) is true, so the wrappers and their helpers stay
gated together. `#'make-array` is a variadic wrapper (`variadicMakeArray`)
whose runtime keywords are re-extracted with `getf`: a `:displaced-to`
argument selects the bare-view shape, everything else the general
`:adjustable`/`:fill-pointer`/`:initial-element` shape, and `:element-type` is
accepted and ignored like the call position -- this is what makes
cl-utilities' verbatim `copy-array` (`apply #'make-array (list* dims
options...)`) work on the compile path.

Interpreter/JVM errors carry `fn: message` text (e.g. "vector-pop: empty
vector"); WASM traps (`unreachable`) on the same conditions. Compiled `list`
argument evaluation is right-to-left (.todo/014), so tests/ci-spec sequence
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

That holds for STRINGS too, and the interpreter was the odd one out until
2026-07-26: `Environment.charRef` (which backs `char` / `schar` / `aref` on a
string) and `%schar-set` bounded the index by `LispString.length()`, i.e. the
fill pointer, so `(aref s 5)` on a `:fill-pointer 3` string of capacity 8
signalled on the interpreter and returned the inactive slot on all three compile
backends. Both now bound by `capacity()`. CL is explicit that `char`, `schar` and
`aref` ignore fill pointers ("it is permissible to use `aref` to access any array
element, whether active or not"), so the compile backends were right and the
divergence was silent -- no test covered an inactive string slot. Pinned now by
the `string-fill-pointer-inactive-slots` ci-spec case (read AND write through
`aref`, all four backends byte-identical).

**`char` / `schar` past the fill pointer are still three-way divergent, and the
ci-spec case deliberately does not cover them**: the interpreter now returns the
slot, the JVM throws a raw `String.offsetByCodePoints` exception, and both WASM
backends return `#\Nul` (they materialize the string at its fill-pointer length
and have no bounds check at all -- writing past the end silently APPENDS there).
That is the general missing-bounds-check gap, not a fill-pointer question;
`.todo/186` holds it with the measurements.

Displacement: `displacedTo` (a `LispArray` or null) + `displacedOffset` fields;
a displaced array's `data` is a shared empty array and all element access goes
through `readFlat`/`writeFlat`, which walk the chain adding each hop's offset
(so growth of the target's storage is followed -- the chain holds the OBJECT,
not the storage). `totalSize()` (dims product) replaced `data.length` in every
bounds check; `effectiveLength()` = fp or `totalSize()`. `become(other)`
replaces dims/data/fillPointer in place (adjust-array's adjustable half).

`make-array` parses `:fill-pointer` (`t` = size, integer = that value, `nil` =
none; rank-1 only), `:adjustable`, and ignores unknown keywords such as
`:element-type`. New builtins live in `Environment.registerArrays`.

### JVM (implemented)

An array is still a `java.util.ArrayList`, but slot 0 now holds a header
`Object[]{dims, fillPointer, adjustable}` -- `dims` the `Object[]` of Long
dimension sizes (length = rank), `fillPointer` a `Long` or null, `adjustable`
the RAW `:adjustable` argument (null = nil; kept verbatim so
`_adjustableArrayP` is a null test). Data stays at slots `1..` (the `1 + flat`
offset in `_aref*`/`_aset*` is untouched); every dims reader gained one extra
`aaload 0` (`emitFlat2`, `emitFlatN`, `_arrayDims`, `buildToString`,
`JvmLengthRuntimeBuilder`). BOTH header producers build the wrapper:
`_arrayMake` (signature grew to `(dims, init, fillPointer, adjustable)`;
`JvmArrayCompiler.compileMake` compiles the keyword value expressions or pushes
null) and `JvmQuoteCompiler.compileQuotedArray` (literals: slots 1/2 null).
`buildToString` + `_length` clamp the element count to the fill pointer.

A DISPLACED array carries a 5-element header
`Object[]{dims, null, null, target, offsetLong}` and holds NO data slots
(`_arrayMakeDisplaced(dims, target, offset)`, bounds-checked against the
target's dims product). Every data access now funnels through the two
displacement-aware primitives `_rmGet(list, idx1based)` / `_rmSet(...)`: a loop
`while header.length > 3 && header[3] != null { idx += header[4]; list =
header[3] }` (the offset composes with the 1-based list index directly), then
`get/set(1+flat)`. `_aref1/2/N`, `_aset1/2/N` and `buildToString`'s element
read end in `invokestatic _rmGet/_rmSet` (the builders take the generated
class's `selfClass` for the methodref); `buildToString`'s no-fp element count
and `_length`'s no-fp fallback switched from `size() - 1` to the dims product /
`dims[0]` (same value for ordinary arrays, correct for displaced).
`_arrayDispTarget`/`_arrayDispOffset` read header slots 3/4;
`_arrayBecome(a, b)` copies dims+fp into a's header and resizes/copies the
ArrayList elements in place.

New static helpers in `JvmArrayRuntimeBuilder` (same array gate):
`_fillPointer`, `_setFillPointer`, `_arrayHasFillPointer`, `_adjustableArrayP`,
`_vectorPush`, `_vectorPop`, `_vectorPushExtend` (push-extend appends nulls to
the ArrayList and updates the inner dims[0]), `_rmGet`, `_rmSet`,
`_arrayMakeDisplaced`, `_arrayBecome`, `_arrayDispTarget`, `_arrayDispOffset`.
Call sites are wired in `JvmArrayCompiler` + `JvmExprCompiler.compileCons`;
`--optimize` (`JvmClassShaker`) keeps used helpers via the ordinary
invokestatic call graph (verified with `--optimize` on fill-pointer and
displaced/adjust-array programs).

### WASM (implemented)

The `TYPE_CELL` box now holds a header `TYPE_CONS` of
`(dims . (meta . data))`: `dims`/`data` are the same `TYPE_HASH_BUCKETS`
arrays; `meta` is `(fillPointer-i31-or-null . (adjustableRaw . offset-i31))`
(the offset is 0 for an ordinary array). The header's CAR is still the dims
buckets array, so the array-vs-hash-table discriminator used by `%arrayp` /
`WasmLengthCompiler` / the printer is unchanged, and
`compileDims`/`emitFlatIndex` (car readers) needed no change. Producers:
`compileMake` (resolves `:fill-pointer` at runtime -- null / bounds-checked
i31 / `t` -> dims[0] -- only when the keyword appears at the call site) and
`WasmQuoteCompiler.compileQuotedArray` (meta `(null . (null . 0))`).

A DISPLACED array stores the TARGET CELL in the data slot (instead of a
buckets array) and its offset in the meta chain
(`compileMakeDisplaced`; bounds-checked against the target's dims product,
traps when too small). Every data access site
(`compileAref`/`compileAset`/`compileRowMajorAref`/`compileRowMajorAset`) runs
the inline `emitResolveDataAndIndex` walk: while the data slot `ref.test`s as
`TYPE_CELL`, add the meta offset to the flat index and hop to the target's
header -- an ordinary array falls straight through. Because the walk re-reads
each hop's CURRENT header, a view keeps aliasing a target grown in place by
push-extend/adjust-array. The printer (`WasmRuntimeBuilder.emitPrintArray`)
gained the same walk (one extra i32 local, `baseSlot`, in
`buildPrintValBody`/`buildPrincValBody`) and its no-fp element count switched
from the data-buckets length to the dims product; `WasmLengthCompiler` already
used `dims[0]` and needed nothing. `compileAdjustableArrayP` reads
`meta.cdr.car`; `%array-become` is three inline `struct.set`s;
`%array-disp-target`/`%array-disp-offset` read the data slot (cell or nil) and
`meta.cdr.cdr`. The vector-push family never sees displacement (a displaced
array has no fill pointer, so its fp guard traps first).

The builtins are emitted INLINE in `WasmArrayCompiler`
(compileFillPointer/SetFillPointer/HasFillPointer/AdjustableArrayP/VectorPush/
VectorPop/VectorPushExtend/ArrayBecome/DispTarget/DispOffset; push-extend
copies into a fresh buckets array with a loop and `struct.set`s the inner cons
+ dims[0]) -- no new heap type, no new `FUNC_*` index, so the component blobs
are untouched and Preview 1 / `--component` stay identical.

### `--no-gc` scalar WASM

Unsupported, like every array operation on the scalar backend: the eligibility
scan (`NoGcWasmCompiler.collectCallsCons`) names the operation in a clear
compile error ("--no-gc: unsupported operation 'vector-push' in function 'f'
..."). Documented under the `--no-gc` section of `doc/*/compiling/wasm.md`
("vectors" in the ineligible list).

## Wiring points (adjust-array / displacement)

`LispNames`: `ADJUST_ARRAY`, `ARRAY_DISPLACEMENT`, `ARRAY_BECOME`
(`%array-become`), `ARRAY_DISP_TARGET`/`ARRAY_DISP_OFFSET`
(`%array-disp-target`/`-offset`), `DISPLACED_TO_KEYWORD`,
`DISPLACED_INDEX_OFFSET_KEYWORD`. `PackageRegistry`: the two public names in
`CL_FUNCTIONS` (list-functions count 236 -> 238), the three `%`-names in
`CL_INTERNALS`. Both compilers' `programUsesAnyArrayOp` gates list all five
names. `BuiltinFunctionWrappers`: `binary(ADJUST_ARRAY)` (2-arg form) +
`unary(ARRAY_DISPLACEMENT)` (primary value only) in the
`ARRAY_FILL_POINTER_FUNCTIONS` group. `--no-gc` rejects the new names through
its default unknown-operation error.

## Tests / docs

- Interpreter: `LispEvaluatorTest` -- `fillPointerLengthAndAccessors`,
  `fillPointerVectorPrintsUpToFillPointer`, `vectorPushStoresAndReturnsIndexOrNil`,
  `vectorPushThenReadBack`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`,
  `setfFillPointer`, `simpleVectorHasNoFillPointer`,
  `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`,
  `adjustArray*`, `displacedArray*`, `arrayDisplacementReturnsTargetAndOffset`,
  `makeArrayDisplacedErrors`.
- JVM: `JvmLispCompilerTest.compileFillPointer*` / `compileVectorP*` /
  `compileSetfFillPointer` / `compileSimpleVectorHasNoFillPointer` /
  `compileFillPointerFirstClassWrappers` / `compileClUtilitiesCopyArray` /
  `compileAdjustArray` / `compileDisplacedArrays` /
  `compileArrayDisplacementValues` /
  `compileMakeArrayDisplacedKeywordComboIsACompileError`.
- WASM: the same set in `WasmLispCompilerIntegrationTest`.
- E2E: ci-spec `fill-pointer-arrays-cross-backend` +
  `adjust-displaced-arrays-cross-backend` (all four backends).
- Docs: `reference/functions/{fill-pointer,array-has-fill-pointer-p,
  adjustable-array-p,array-element-type,vector-push,vector-pop,
  vector-push-extend,adjust-array,array-displacement}.md` (en+ja) + the
  make-array page + the functions table.
- The `list-functions` count (238) is pinned in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, and the
  `rontolisp-package-introspection` ci-spec case.
