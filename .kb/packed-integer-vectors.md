# Packed integer vectors ((unsigned-byte 8|16|32) rank-1 arrays)

**Invariant: `(make-array n :element-type '(unsigned-byte 8|16|32))` -- rank 1, no fill
pointer / adjustability / displacement, LITERAL element type -- builds a PACKED unsigned
integer vector with identical element semantics on every backend: a store MASKS to the
element width, a read widens UNSIGNED, a non-integer store is a type error
(interpreter/JVM signal, WASM traps).** Any other combination keeps the general boxed
representation, which still REMEMBERS the element type ([array-literals.md](array-literals.md)).
Rank-n falls back for every specialized type except packed floats; CHARACTER degrades the same.

## Representation
- Interpreter `LispIntVector`: `int width` (8/16/32) + pre-masked `long[]`.
- JVM: bare `long[]{width, e0, ...}`, `instanceof long[]` the free discriminator;
  `JvmIntArrayRuntimeBuilder` `_ivAref1`/`_ivAset1`/`_ivDims`/`_ivLength`/`_ivToGeneral`/
  `_ivElementType`/`_ivMake`/`_ivAlike`/`_ivRequireGeneral`, gated on `Ctx.usesIntArray`
  (gate off = byte-identical build); dispatch chains iv -> fv -> general.
- wasm-GC: the BARE `TYPE_I8ARR`/`TYPE_I16ARR`/`TYPE_I32ARR`, `(array (mut i8|i16|i32))`,
  types 57-59 in ONE rec group (keeping i32 structurally distinct from `TYPE_LIMBS` under GC
  canonicalization); no wrapper, no dims, `ref.test` discriminates width. `TYPE_IV_SET` (60)
  is `_iv_set`'s signature; `IARR_TYPE_LAST` shifts the `--simd`/async/instance blocks past
  all four, `FUNC_IV_SET` follows `FUNC_FX_REM` inside `FX_FUNC_LAST`. `--no-gc`: no arrays.
- **Small-buffer-oriented**: interpreter/JVM spend 8 bytes an element whatever the width (the
  width is a DISCRIMINATOR, not a packing scheme); only wasm-GC packs at width. 1.6-4.3
  Gelem/s on a 1 Mi-element chunk, 1.3-1.7x slower than a real `short[]`
  ([binary-sequence-io.md](binary-sequence-io.md)); stage huge buffers in chunks.

## "Literal" includes a `deftype` alias of one
`LispMacroExpander.resolveElementTypeAlias(spec, closRegistry)` is the one resolver: strips
the compile paths' quote, follows alias chains (16 hops, terminating `(deftype a () 'a)`),
returns its argument UNCHANGED otherwise so an alias-free program stays byte-identical. Sites:
`Environment.makeArrayBuiltin(closRegistry)` (re-registered by `LispEvaluator` with the LIVE
registry, the `concatenateBuiltin` arrangement); `JvmArrayCompiler.compileMake` plus the gates
`JvmLispCompiler.makeArrayIsPackedInt`/`makeArrayIsPackedFloat`; `WasmArrayCompiler`'s
`compileMake`/`packedIntElementWidth`, which accepts the bare list as well as the wrapper.
- **Trap: a gate that misses the alias omits `_iv*`/`_fv*` and silently takes the general path.**
- Deliberately unresolved: a RUN-time designator
  (`LispMacroExpander.lowerRuntimeElementTypeMakeArray`) and `open`'s `:element-type`
  (`isBinaryElementTypeLiteral`). Pinned by ci-spec `make-array-element-type-deftype-alias`.

## Shared semantics
- A masked store returns the value AS STORED; a `TYPE_BIGINT`-tier store contributes its LOW
  32 bits everywhere; an out-of-range index errors (WASM traps).
- `typep` for `(simple-array (unsigned-byte 8) (*))` tests `%arrayp` AND
  `(equal (array-element-type x) '(unsigned-byte N))`, dimensions unchecked -- so a general
  `#(...)` is never a `(vector (unsigned-byte 8))` (s-sql's `sql-escape` dispatches on it).
- `subseq`/`copy-seq` are TYPE-PRESERVING via `%array-alike` (`LispNames.ARRAY_ALIKE`, in
  `CL_INTERNALS`); `replace` mask-stores element-wise; `coerce`/`concatenate` pack when the
  RESULT TYPE asks ([concatenate-result-families.md](concatenate-result-families.md));
  `reverse`, `remove`, `map 'vector` and printing return GENERAL everywhere, the interpreter's
  `seqResult` deliberately rebuilding general to match the compilers.
- Fill-pointer / `adjust-array` / displacement MUTATORS error (`requireGeneralArray` /
  `_ivRequireGeneral`); the read-only probes are an UNPINNED divergence (`nil`/`0` on the
  interpreter, error on JVM, trap on wasm) -- keep out of ci-spec. A packed vector IS a valid
  `:displaced-to` target ([adjustable-arrays.md](adjustable-arrays.md)); the view stays general
  but its elements mask. `equal`/`eql` are identity only.

## The reader literal
`#N@(...)` ([reader-features.md](reader-features.md)) reads packed for N in {8, 16, 32}
(elements masked), plain `#(...)` otherwise: `Token.IntVectorOpen`, `LispReader.readIntVector`,
baked by `WasmQuoteCompiler.compileIntVectorLiteral` / `JvmQuoteCompiler.compileLiteralIntVector`.
`PureBuiltinFolder` is a second producer ([pure-builtin-fold.md](pure-builtin-fold.md)).

**A packed literal of 16 elements or more goes into static data at the element width and the
site becomes a copy loop over it: `w/8` bytes an element plus a fixed ~45.** Below 16,
`array.new_default` + one `array.set` per non-zero element. The array is still allocated and
filled at the SITE, so each evaluation yields a fresh, independently mutable vector. Traps:
- Bytes go through `StringTable.appendShakeableBlob` -- the ONE active data segment, 4-byte
  aligned, registered as a `DroppableDataRange` so `--optimize` cuts a dead table. NOT
  `array.new_data`: that needs a passive segment and a datacount section, and `WasmTreeShaker`
  throws on the opcode on purpose (`scanGc`).
- The segment base is an explicit `i32.const`, not the load's memarg offset: the shaker's
  droppable-range probe reads `i32.const`s and skips memargs.
- The loop counter is an i64 scratch local, so an ASYNC resume body falls back to `array.set`s.

## Unboxed fast paths (wasm-GC)
Machinery: [wasm-int-fusion.md](wasm-int-fusion.md).
- `(aref a i)` in a fused tree is an `ArefLeaf`: array/index evaluate once into scratch locals,
  the fast path guards `testIntVector` + i31 index and reads `array.get_u` -> raw i64 (no
  `_int_new`), any other shape bailing to `WasmArrayCompiler.emitAref1FromSlots` from the SAME
  locals.
- `(setf (aref packed i) <integer tree>)` compiles the value RAW
  (`WasmIntFusionCompiler.tryCompileRaw`) and stores through `_iv_set`; in statement position
  (`WasmExprCompiler.compileForEffect`) the value is never materialized, so the hot-loop store
  allocates NOTHING.
- Fused-call defun inlining: a uniquely-defined fixed-arity defun whose single body expression
  is a closed integer-op tree over its parameters (whitelist
  `+ - * mod rem logand logior logxor lognot ash 1+ 1- ldb byte aref`;
  `WasmIntFusionCompiler.isInlinableDefun` -> `Ctx.inlinableDefuns`, NEVER under `--dynamic`)
  is spliced into fused trees at direct call sites; a parameter used more than once is demoted
  to a shared once-evaluated leaf, leaf registration happening at classify time in source order
  to preserve argument evaluation order. An asyncMode `rontolisp:async-defun` is excluded even
  when its rewritten plain defun qualifies textually ([async-await.md](async-await.md)).

## The heap-type encoding landmine
`WasmWriter.writeHeapType` treated any value >= 0x40 as an abstract heap-type code, so
`ref.test`/`ref.cast`/`ref.null` of type indices 64+ produced "invalid heap type" modules. The
boundary is now 0x60 (the abstract range starts at `exn` 0x69), so indices up to 95 encode
correctly. **Trigger: if such a target ever reaches index 96, split the int-parameter
disambiguation into `writeAbstractHeapType`/`writeTypeIndex`.**

## Tests
- `packedIntVector*` in `LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest`; ci-spec `packed-integer-vectors`;
  `WasmLispCompilerTest.aLiteralLookupTableCostsItsOwnBytesAndNotThreeTimesThem`.
