# Fill-pointer / adjustable / displaced arrays (`copy-array` surface)

The extended-array surface cl-utilities' `copy-array` touches (`make-array`'s `:fill-pointer` / `:adjustable` / `:displaced-to` / `:displaced-index-offset`, `fill-pointer`, `vector-push(-extend)` / `vector-pop`, `adjust-array`, `array-displacement`, ...) is identical on all four backends. `--no-gc` rejects all of it (`NoGcWasmCompiler.collectCallsCons`).

## Shared semantics
- Only rank-1 arrays have a fill pointer. It is the effective length (`length`, printing, the sequence view); `aref` / `row-major-aref` reach the FULL backing store, strings included.
- `vector-push-extend` grows any fill-pointer vector regardless of `:adjustable`; `adjustable-array-p` reports the flag VERBATIM, so a displaced view diverges from SBCL (`T` for any non-simple array). Both conforming.
- `array-element-type` returns the REMEMBERED requested type; a displaced view's is its TARGET's. On the compile path it is the LITE `(if (stringp x) 'character t)` (`expandArrayElementType`) unless `usesFloatArray || usesIntArray || usesTypedArray`.
- A vector without a fill pointer, an out-of-range fill pointer and an empty `vector-pop` signal the same condition everywhere: [error-handling.md](error-handling.md), "A vector without a fill pointer".
- `#'make-array` re-extracts its keywords with `getf` (`fullMakeArray`), so a character request is a string as in call position (SBCL 2.2.9; ci-spec `make-array-function-value-takes-every-keyword`). Spliced only where the value may get more than one argument (`mayPassMoreThanOneArgument`); elsewhere `variadicMakeArray` IGNORES `:element-type`.
- **Trap: `char`/`schar` PAST the fill pointer are three-way divergent**, deliberately uncovered: interpreter returns the slot, JVM throws a raw `String.offsetByCodePoints` exception, both WASM return `#\Nul` (no bounds check; writing past the end silently APPENDS).

## Strings as arrays
`make-array :element-type 'character` builds a mutable string everywhere. The marker survives `adjust-array` (become mutates the header in place): interpreter a mutable `LispString`; JVM a **length-4** header `Object[]{dims, fp, adj, null}`; WASM **meta offset i31 == 1**.

- **Mutability is the MARKER's, not the fill pointer's**: without `:fill-pointer` the slot is NIL everywhere, so `(array-has-fill-pointer-p (make-string 3))` is NIL -- what `simple-string-p` asks (`.kb/declarations-type-checks.md`).
- **A plain `(make-string n)` IS a character vector** (`expandMakeString`, `expandMakeSequence`). If it ever builds an immutable string again, `(%arrayp seq)` in `expandReplace` / `expandScharSetFunctional` goes false and `(replace buf x)` in statement position silently discards its result (s-sql sent PostgreSQL a blank string; `PostmodernE2eTest`).
- Hence `Ctx.usesArrays` lists `MAKE-STRING` and `MAKE-SEQUENCE` beside `MAKE-ARRAY`: they lower to it during `compileExpr`, AFTER the scan.
- `eq`/`eql` on a make-string result: content on interpreter and JVM, identity on WASM; unspecified in CL, deliberately uncovered.

**Normalization into the immutable runtime string** is on demand: JVM `_strv`, WASM `_charvec_to_str`, at every string-consuming site and the shared runtime bodies -- NOT char/schar/elt, which read the ELEMENT (`.kb/string-index-cost.md`).

**A PREDICATE does not normalize**: `_charvec_p` (`WasmStringRuntimeBuilder.buildCharvecPBody`) is the only shape decision; `stringp` and `_charvec_to_str` both use it, so the marker invariant has ONE owner. Pinned by `WasmLispCompilerIntegrationTest#compileStringpClassifiesEveryCellShape`. **Unfinished**: other callers and `_strv` re-render per call; caching would need every write to a mutable character vector to invalidate.

**String mutation flows through shared expansions**: `expandReplace`, `expandFill`, `expandScharSetFunctional` branch at runtime on `(%arrayp seq)` -- a vector is written in place, an immutable string keeps the functional rebuild (setf form needs a variable place). The interpreter writes strings in place (`LispString.setCharAt`), a compile-path-only deviation. Default `:initial-element` `#\Space`.

A string LITERAL is never written in place on any backend (`.kb/string-write-runtime.md`). **Unfinished: `replace` / `fill` / `nstring-*` still corrupt a literal on the interpreter** (return value correct on all four).

### Array-info readers accept every string representation
The array shape readers and predicates accept the header-less string, the character vector and the displaced string view everywhere. They lower through `array-dimensions`, so ONE string arm per backend carries them (`_arrayDims`'s `instanceof String` arm AHEAD of the `ArrayList` cast; `compileDims`'s `ref.test TYPE_STRING`); predicates guard the header read with `emitHeaderSlotToBool`. The dimension is the **CAPACITY**, never `length`.

### A COMPUTED `:element-type`
A designator in a variable or from a call (`(stream-element-type s)`) must not fall to the general array: with `stringp` false, `read-sequence` read BYTES from a character stream. `lowerRuntimeElementTypeMakeArray` (BEFORE the `:initial-contents` lowerings) emits a runtime `member` dispatch. "Literal" = a `(quote ...)` form, the unquoted `(unsigned-byte 8)` shape, or a symbol in `LITERAL_ELEMENT_TYPE_NAMES`.

## The JVM array gate is a CONSEQUENCE, not a prediction
**Invariant: on the JVM the array runtime is emitted whenever the emitted bytecode calls it, whichever lowering introduced the call.** Lowerings introduce array primitives during `compileExpr`, after the scan, and lazy resolution turned a miss into `NoSuchMethodError` at first run. The scan still decides `Ctx.usesArrays` (it changes CODE, so it must be known first); `JvmClassSplitter.unresolvedSelfMethods` then reports own-class methods called but not declared, and a name in a group roster (`JvmArrayRuntimeBuilder.METHOD_NAMES`, `JvmHashRuntimeBuilder.METHOD_NAMES`, the eval runtime's) RE-RUNS `compile` with that group forced on; unmatched names fail loudly. Rosters pinned both ways by `JvmRuntimeGroupNamesTest`.

**Each lowering dispatching over sequence representations takes an `arraysExist` flag** (interpreter and WASM `true`, JVM `Ctx.usesArrays`) and drops its array arm when false: `expandCoerce`, `expandElt`, `expandMap`, `seqResultDispatchForm` (and its forwarders), `expandSubseqCompat` (returns `null`), `expandReplace`, `expandVectorp` (whose injected wrapper is in EVERY program until the shaker runs -- ungated, it put `_arrayDims` in `(print 1)`). `expandClassDesignator` takes it too, with `hashTablesExist`.

**The array-gated wrapper set is complete**: the ten wrappers that call the array runtime (`fill`, `coerce`, `vector`, `read-sequence`, `write-sequence`, `svref`, `array-rank`, `array-dimension`, `array-total-size`, `array-row-major-index`) reference-gate wrapper and runtime on the same fact (`.kb/eval-runtime.md`) via `ARRAY_FILL_POINTER_FUNCTIONS`, so the two scans cannot drift.

**`(setf (elt s i) v)` is the worked example**: it carries the three-way dispatch `(setf (aref s i) v)` has (`consp` -> `rplaca`, `stringp` -> the `schar-set` rebuild, else `%aset`); a plain `%aset` on a string was `NoSuchMethodError` on JVM and a cast failure on WASM.

## The growth policy of `vector-push-extend`
The grown capacity is OBSERVABLE (`array-dimension`), so ONE policy for all four backends, in `am.ik.rontolisp.ArrayGrowth` (SBCL 2.2.9's numbers): a supplied `extension` is added verbatim; otherwise the capacity DOUBLES off a floor of `MIN_CAPACITY` = 1; `NO_EXTENSION` = 0 is the "not supplied" sentinel. Compile paths emit the same arithmetic inline against its constants (`_vectorPushExtend`; `compileVectorPushExtend` + `emitDefaultGrownCapacity`). Growing by one would make a push loop quadratic. **wasm-GC moves the old elements with one `array.copy`** (Starlark comprehensions 0.86 -> 0.66 s; `.kb/starlark-frontend.md`, "A sequence at a time").

## A slot the growth OPENS holds the element type's zero
**Invariant: an array slot nobody wrote holds its array's remembered element type's zero, on all four backends, whichever operation opened it (`make-array`, growth, `adjust-array`).** Such slots are below the DIMENSION, so `aref` reads them; a raw null crashed a grown character vector and read `NIL` from a `double-float` one.

One answer per element type (`ArrayElementTypes.defaultElement`), asked of the ARRAY: `%array-default-element` (JVM `_arrayDefaultElement`, reading the same header facts as `_arrayElementType` in the same order; wasm `emitDefaultElementForHeader`, marker 1 checked always, markers 2..7 gated on `Ctx.typedArrayCodes`). `expandAdjustArray` always spells `:initial-element`.

**The character fill is `#\Space`, not SBCL's `#\Nul`** (`.kb/array-literals.md`); a `t` vector keeps `NIL` where SBCL answers `0`, deliberate. ci-spec `opened-slot-fill-cross-backend`.

## The adjusted COPY remembers the element type
**Invariant: `adjust-array` never changes an element type. An `:adjustable` array keeps its identity; a NON-adjustable one answers a FRESH array STAMPED with the old type** by `%array-adopt-element-type (new old)`, which COPIES the type word: re-deriving via a runtime `:element-type` would make every `adjust-array` a seven-arm dispatch and count as every specialized width in `Ctx.typedArrayCodes`. `LispArray.adoptElementType` is the one writer of `elementTypeCode`.

**An immutable string is a legal `adjust-array` argument on the compile paths; a PACKED vector is not** -- interpreter and JVM signal `adjust-array: not applicable to a packed integer vector` / `... packed float array` (`emitRequireGeneralIfPacked`; no packed float array, no `_fvRequireGeneral`, so the default build is byte-identical); wasm traps `cast failure` (no custom trap-message channel).

## `sort`/`nreverse`/`stable-sort` keep a vector's fill pointer and identity
**Invariant: `sort`, `nreverse`, `stable-sort` permute a fill-pointered or adjustable vector/string IN PLACE (same object, fill pointer, flag, total size) on all four backends, as SBCL.**

- `seqResultDispatchForm`'s `destructive` flag is private to the three permuting callers; the non-destructive family (`remove*`, `substitute*`, `reduce`, `remove-duplicates`) must NOT become identity-preserving.
- **The destructive rebuild answers `(replace __seq_in <rebuild>)`'s result, not `__seq_in`**: for a string literal `replace` answers a COPY, so `__seq_in` would be the unsorted literal.
- The interpreter takes `Environment.seqResultDestructive` (its native operators never use the expander's dispatch) and writes nothing beyond the active length.

**`delete` / `delete-if(-not)` / `nsubstitute(-if(-not))` on a vector/string** route through a runtime check (`deleteOrSubstituteDispatch`) into the `remove`/`substitute` family, so the result is FRESH (they used to be silent no-ops). On the interpreter three paths per family move together (`Environment.removeValues`/`substituteValues`, `LispEvaluator.deleteIfValues`/`nsubstituteIfValues`, the `evalCons`/`compileCons` expansion). **Unfinished**: `eq`-preserving in-place compaction.

## adjust-array
Elements preserved per subscript valid in BOTH shapes; `:adjustable` adjusts IN PLACE (`eq`), else a fresh array; the old fill pointer carries over unless given (range-checked); rank mismatch and unknown keywords signal (`program-error`). `:initial-contents` with `:initial-element` is rejected; `:initial-contents` passes the source's element type at runtime so a character source answers a string.

`:displaced-to` (with `:displaced-index-offset`, not with `:initial-element`/`:initial-contents`): an `:adjustable` source becomes the view IN PLACE (`%array-become-displaced`, reusing `_arrayMakeDisplaced`'s / `compileMakeDisplaced`'s shape so the view is identical to a fresh `make-array` view), a non-adjustable one answers a fresh displaced array, matching SBCL. ci-spec `adjust-displaced-arrays-cross-backend` pins identity.

The n-dimensional copy stays OUT of per-backend codegen: interpreter `Environment.adjustArray` over `LispArray.become`; compile path `expandAdjustArray` over `%array-become`, `%array-default-element`, `%array-adopt-element-type` and an UNCONDITIONAL `%array-undisplace`.

**Ordering trap**: `%array-undisplace` must be part of `a`'s own `let*` binding (`(a (%array-undisplace <array-expr>))`), not a later statement. Every binding reading `a` -- above all `newArr`'s element-type stamp -- must see the undisplaced state: that word is the OFFSET while displaced, so read too early it silently answers "remembers nothing". It runs on every argument including an immutable string, so `_arrayUndisplace` / `compileArrayUndisplace` open with a string check.

`%array-undisplace` and `%array-become-displaced` are compile-path-only (no `Environment` registration: the interpreter does not expand `adjust-array` through `LispMacroExpander`).

## Displacement (`:displaced-to`)
No `:initial-element` / `:initial-contents` beside `:displaced-to` (compile-time `UnsupportedOperationException` on JVM/WASM, runtime error on the interpreter). Bounds-checked at creation (`total + offset <= target total`); rank may differ; chains resolve through the CURRENT storage of each hop, so a view keeps aliasing an adjustable target grown in place.

`array-displacement` returns two values via the syntactic multiple-value tier (`isMvProducerForm` / `lowerMvProducer` over `%array-disp-target` / `%array-disp-offset`); no `%mv-spill`.

### Displacing a STRING
A view over a STRING is a **string view**: `stringp`, its own length, the target's characters from the offset, NO copy; views chain.

**The TARGET decides the shape, not `:element-type`**: the portable substring idiom (cl-ppcre's `nsubseq`, hence `regex-replace` with a function replacement and every `:sharedp t`) passes a RUNTIME `(array-element-type sequence)`, so both compilers keep the `:displaced-to` branch AHEAD of the runtime-element-type lowering (`ClPpcreE2eTest`).

**Writing through a view reaches the target's storage when it has one** (a character vector, which `copy-seq`/`subseq` results are: `.kb/string-write-runtime.md`). Over a LITERAL a view reads through and the first write PROMOTES the target slot to a character vector, leaving the literal untouched. **Invariant, all four backends: the promoted slot is the target of the chain's LAST view (whose target is the literal), so a view of that view shares the copy, another view of the literal does not, and `array-displacement` of the promoted view answers the copy.** SBCL writes the literal (CLHS 3.7.1 leaves it undefined). Interpreter: `LispString.writableStorage` repoints the last view under that view's monitor. Pinned by ci-spec `displaced-string-views-cross-backend` (last three lines).

Representations: interpreter `LispString.displacedTo` / `displacedOffset` / `viewLength` (a view has no fill pointer and is not adjustable); JVM header length **7** `{dims, null, null, target, offsetLong, null, null}`, `_rmSet` promoting through `_strToCharVec` (under the view's monitor where threads run, see "Where another thread can run the program's Lisp" below); WASM the target string in the data slot. Offsets in CHARACTER units.

### A displaced view is not a BARE view
**Invariant: `make-array :displaced-to` accepts `:fill-pointer` and `:adjustable` on all four backends; the fill pointer is the VIEW's own**, resolved against the view's element count.
- `vector-push` / `vector-pop` go THROUGH the displacement-aware element primitives (`_rmGet`/`_rmSet`, `_arr_get`/`_arr_set`, `readFlat`/`writeFlat`); a view has no storage of its own.
- **A full view UN-DISPLACES when it grows** (SBCL 2.2.9): contents copied into own storage, `array-displacement` then nil/0 (`undisplace`, `_arrayUndisplace`, `_arr_undisplace`).
- Shape and element type survive: JVM header 7 -> 4, 5 -> 3 or 5 with the resolved element type in the freed offset slot; wasm's freed offset word becomes the chain end's marker.

### The element type of a view, and of what it un-displaces into
**Invariant: a view answers the element type of the array at the END of its chain, and un-displacing never changes that answer, on all four backends.** Interpreter inherits `target.elementTypeCode` at birth; JVM `_arrayElementType` hops the chain and `_arrayUndisplace` walks it into a length-5 `{dims, fp, adj, null, et}`; wasm `emitRememberedElementType` opens with `emitResolve`'s walk. Slots a growth then opens take the resolved type's zero.

**Two SBCL oracle traps**: SBCL ENFORCES element-type compatibility (rontolisp does not), so write repros with the view spelling the target's `:element-type`; and SBCL constant-folds `array-element-type` of a mismatched view, deleting the `make-array` -- read an element to force the array to exist.

### A PACKED vector is a displacement target
**Invariant: `make-array :displaced-to` accepts a packed integer vector or packed float array, on all four backends, the view using the target representation's element semantics.** An ordinary general-array view whose data slot holds the packed value -- no new representation, header length or marker. JVM `_rmGet`/`_rmSet` dispatch `long[]` at `[1 + flat]` and `double[]`/`float[]` at `[1 + rank + flat]` before the String arm. The per-program cost of those arms is deliberately NOT gated.

**Deliberate SBCL divergences**: an out-of-range integer stored through the view MASKS to the element width (`.kb/packed-integer-vectors.md`); element-type compatibility unchecked. **Unpinned edge**: the VALUE a store through a view answers differs (interpreter as given, JVM as stored); the stored element is the same.

## Representation
**Interpreter (`LispArray`)**: `fillPointer` `-1` = none; `data`/`dimensions` non-final for growth; displaced arrays hold a shared empty `data` and access goes through `readFlat`/`writeFlat`, walking the chain of OBJECTS (not storage), so target growth is followed.

### JVM
An array is a `java.util.ArrayList` whose slot 0 is a header `Object[]{dims, fillPointer, adjustable}` (`adjustable` the RAW argument); data at slots `1..`. **Header LENGTH is the tag**: 3 ordinary boxed, 4 character vector, 5 displaced OR boxed with a remembered element type (told apart by slot 3; `.kb/array-literals.md`), **6 packed**, 7 displaced string view. A displaced header `{dims, fp, adj, target, offsetLong}` holds no data; every access funnels through `_rmGet` / `_rmSet`, whose element counts use the dims product, not `size() - 1`.

**A PLAIN general array starts PACKED**: no fill pointer, not adjustable, initial element nil or an in-range integer (decided at runtime in `_arrayMake`) -> only a length-6 header `{dims, null, null, null, null, long[] data}`, `Long.MIN_VALUE` the nil sentinel. The first store that cannot pack calls **`_arrayWiden`**; the ArrayList IS the identity, so widening is invisible. **Two non-helper consumers move with any change to the tag**: `JavaBridgeTemplate.marshal`'s packed branch and `JvmIntFusionCompiler`'s aref leaf (`.kb/jvm-int-fusion.md`).

**Where another thread can run the program's Lisp, a packed array holds its elements boxed as well.** Gate: `JvmLispCompiler`'s `lispOnOtherThreads` (`.kb/dynamic-special-variables.md`, "One thread") passes `concurrent` to `JvmArrayRuntimeBuilder.build` / `JvmBulkRuntimeBuilder.build`. **Invariant: in such a program every store into a packed array's `long[]` also stores into its list, a packed list is born full, and `_arrayWiden` only swaps the header (after a `VarHandle.releaseFence`); a program without the gate is byte-identical.** No reader changed. Without it, a thread could read the new header before the boxed elements were appended (`IndexOutOfBoundsException`), and stores were lost (7-10 of 10 rounds wrong).
- Wart, of a race the program has: two threads storing into ONE element at once can leave one value in the `long[]` and the other in the slot; JMM-allowed for a racy plain access.
- Cost (program holding a `make-thread`): sieve of 2,000,000 57-73 -> 86-104 ms; reads unchanged; stores of 0..1023 24-27 -> 70-200 ms (the box is kept, the slot store takes the GC barrier).
- Rejected (measured): **no packing where threads run** -- every read pays (reads 10-11 -> 22-27 ms), no store gains; **a fence after each packed store** -- no cheaper, and every boxed-shape reader would need an acquire after its header read.
- **The same gate covers a string view's promotion**: with `concurrent`, `emitResolveDisplacement` reads each hop's slot 3 once into a local, and the promotion runs under the view's list monitor, re-reads slot 3 and stores the vector after a `VarHandle.releaseFence`. **Invariant: a view's target is promoted once, whichever thread stores first; without the gate byte-identical.** Before: a `ClassCastException` (ArrayList to String) or lost stores, 99-100 of 100 rounds wrong for a 4000-character view. Cost within noise. No other element-access store replaces part of a header. Pinned by `JvmThreadTest#twoThreadsStoringThroughAViewOfALiteralBothLand` (JVM only; the interpreter's promotion is unpinned).
- Pinned by `java-spec.yaml` `threads-are-started-joined-and-hold-monitors` on all backends and the component (`storedApart`; a missing slot store in `_rmSet` or `_bulkCopy` shows on every run).

### WASM
The `TYPE_CELL` box holds a header `TYPE_CONS` `(dims . (meta . data))`, `dims`/`data` `TYPE_HASH_BUCKETS` arrays, `meta` = `(fillPointer-i31-or-null . (adjustableRaw . offset-i31))`, offset 1 the character-vector marker. The header's CAR is the dims array, so the array-vs-hash-table discriminator is unchanged. A DISPLACED array stores the target CELL in the data slot; every access (`compileAref` / `compileAset` / `compileRowMajorAref` / `compileRowMajorAset`, `emitPrintArray`) runs `emitResolve`, re-reading each hop's CURRENT header.

### What an element access costs (wasm-GC)
Measured on wasmtime 49: **a passing `ref.test` / `ref.cast` / `br_on_cast` is about 1 ns**, a failing one about 1.7 ns; a call 2-5 ns with references live across it (spilled for the collector). Every hop of `cell -> header cons -> (meta . data) -> buckets` is a test: a general `(aref v i)` through `_idx_ref` + `_arr_get` was some 35 tests and two calls, 28 ns. The guest profiler hides this (`.kb/wasm-int-fusion.md`, "Profiling trap"); machine code per instruction: `.kb/wasm-int-fusion.md`, "An integer test compiled twice".

**Invariant: the cell of a rank-1 general array with storage of its own names that storage and its meta cons in two typed fields (`CELL_STORAGE_FIELD`, `CELL_META_FIELD`), null in every other cell (a view, another rank, a hash table, a closure's box), and `_vec_sync` (`WasmArrayRuntimeBuilder.buildVecSyncBody`, `FUNC_VEC_SYNC`) is their one writer.** At the speed levels an `aref` / `%aset` whose representation nothing pins first asks inline whether the cell has storage and the subscript is a fixnum below its length (`WasmArrayCompiler.emitSimpleVectorAccess`): one heap-type test, one i31 test, then `array.get`/`array.set`; anything else takes the size-level access, which checks and reports. Also used by a fused tree's aref fallback and `WasmIntFusionCompiler.emitGeneralArefRead`.

- **Callers of `_vec_sync`** (grep `FUNC_VEC_SYNC`, `emitNewArrayCell`): every site that makes a general array, and every site that replaces dimensions, data slot or header (`%array-become`, `%array-become-displaced`, `%array-undisplace`, growth). It sets the fields only for a one-dimension header whose data is a buckets array exactly `dims[0]` long (so the access may bound by the storage length), else nulls them.
- **`_vec_sync` answers the cell it cast, not its parameter**: answering the parameter made `am.ik.wasm.WasmRefTypeFolder` see "anything", and every access kept the full representation dispatch (ci-spec corpus module +1.7%, caught only by module size).
- **Null fields are always sound, stale ones are not**: a new emitter that stores into a header's car, `(meta . data)`'s cdr or the cell's field 0 must call `_vec_sync` behind the store. The meta cons itself is never replaced, so fill-pointer/marker stores need no call.
- **Every cell carries the fields; a subtype costs more than it saves**: a cast of a subtype to `TYPE_CELL` is no exact match (8.7 ns). Heap cost: none on wasmtime 49, 24 -> 40 B on V8 13 without pointer compression (`.kb/wasm-gc-object-size.md`).
- **The storage is held in a typed local** (`Ctx.Scratch.STORAGE`, `.kb/wasm-unboxed-locals.md`, "The i64 locals run"): Cranelift does not merge two casts of one `(ref null eq)` local (30M reads 0.53 -> 0.425 s).
- **Type order**: `TYPE_HASH_BUCKETS` is the last member of the first rec group, since the cell's field names it.
- **`vector-push-extend` with room** (`emitPushWithRoom`) and **`vector-pop` with an element** (`emitPopWithElement`) use the same fields; anything else (extension argument, full, view, no fill pointer, empty) takes the old path, which reports. Push 30M 1.64 -> 0.49 s. ci-spec `displaced-fill-pointer-cross-backend`. **`length`**: `.kb/length-runtime.md`.
- Measured, 30M rounds (dispatch -> walk -> fields): read 1.77 -> 0.65 -> 0.43 s, store 2.92 -> 0.79 -> 0.56 s; `make-array` +3.5 ns for the `_vec_sync` call.
- **Rejected: one shared helper answering the element array**: slower (the call) for half the size gain. The arm in a fused tree's fallback is needed too (`for x in xs` reads there).
- **A store through a variable asks `stringp` first** (`(setf (aref var i) x)`, see the worked example above): `_charvec_p` tests the marker first (seven casts). A non-variable place or a variable of pinned kind is not asked (`.kb/starlark-frontend.md`, "The cell names its storage").
- A subscript's type check is an i31 test at the site, `_idx_chk` behind it (`compileSubscript`).
- **Trap: a store's value form that leaves the block around it** (`(setf (aref v 0) (return 7))`): the three-arm store evaluates subscript and value inside each arm's `if`, which must be counted in `Ctx.wasmCtrlDepth`, else the exit branches two levels short. `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE`'s last row.
- Size cost: zlib (packed vectors, the worst case) +7% at default level, ~0 at `--optimize=size`; the ci-spec corpus module slightly smaller.
- Pinned by `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE` (three backend suites, every level, the component) and ci-spec `a-vector-element-and-a-structure-slot-through-a-parameter`: rows that read, store, push and take the length of a vector whose storage, dimension or header was replaced under it (six fail on wasm with `_vec_sync` made a no-op). The printed vector is no witness: `row-major-aref` has no arm and reads the header.

## Wiring points (adjust-array / displacement)
`LispNames` constants, `PackageRegistry` (`CL_FUNCTIONS` / `CL_INTERNALS`), both compilers' `programUsesAnyArrayOp`, `BuiltinFunctionWrappers` (`binary(ADJUST_ARRAY)`, `unary(ARRAY_DISPLACEMENT)`, primary value only).

## Tests / docs
- `LispEvaluatorTest`: `fillPointerLengthAndAccessors`, `fillPointerVectorPrintsUpToFillPointer`, `vectorPush*`, `vectorPop`, `vectorPushExtendGrowsBeyondCapacity`, `vectorPushExtendGrowthPolicyIsDoubling`, `aSlotOpenedByGrowthTakesTheElementTypeZero`, `setfFillPointer`, `simpleVectorHasNoFillPointer`, `fillPointerOnNonFillPointerVectorSignals`, `clUtilitiesCopyArrayRunsOnInterpreter`, `adjustArray*`, `displacedArray*`, `displacedStringView*`, `arrayDisplacementReturnsTargetAndOffset`, `makeArrayDisplacedErrors`, `aDisplacedViewCarriesItsOwnFillPointerAndAdjustableFlag`, `aFullDisplacedViewUndisplacesWhenItGrows` (their compiled twins: the ci-spec cases of the same kebab-case names), `unDisplacingKeepsTheViewsOwnElementType`, `aDisplacedViewAnswersItsTargetsElementType`, `aPackedVectorCanBeADisplacementTarget`, `aViewOverAPackedTargetUndisplacesWhenItGrows`, `anAdjustedCopyKeepsTheElementType`, `adjustArrayUndisplacesADisplacedArgument`, `evalSortNreverseStableSortKeepFillPointerAdjustableAndIdentity`, `evalDeleteNsubstituteFamilyOnVectorsAndStrings`, `theArrayShapeReadersAcceptEveryStringRepresentation`, `adjustArrayInitialContentsAndDisplacedTo`. `JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` carry the `compile*` twins plus `compileFillPointerFirstClassWrappers`, `compileClUtilitiesCopyArray`, `compilePackedGeneralArray*`, `compileStringp*`, `compileAdjustArrayInitialContentsAndDisplacedTo`, `anArrayFreeProgramReferencesNoArrayRuntimeHelper`, `namingOneOfTheArrayWrappersBringsTheArrayRuntimeBack`, `JvmRuntimeGroupNamesTest`.
- E2E: ci-spec `fill-pointer-arrays`, `vector-push-extend-growth`, `opened-slot-fill`, `adjust-displaced-arrays`, `displaced-string-views`, `displaced-fill-pointer`, `setf-elt`, `adjusted-copy-element-type`, `adjust-array-undisplaces`, `string-array-shape-readers`, `mutable-strings`, `make-string-mutability` (all `-cross-backend`), plus `sort-nreverse-stable-sort-keep-fill-pointer-adjustable-and-identity`, `delete-and-nsubstitute-family-on-vectors-and-strings`, `ehAStructAccessorOnANonInstanceSignalsATypeError` and the shared-substring lines of `ClPpcreE2eTest`.
