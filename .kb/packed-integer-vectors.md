# Packed integer vectors ((unsigned-byte 8|16|32) rank-1 arrays)

**Invariant: `(make-array n :element-type '(unsigned-byte 8|16|32))` -- rank 1, no fill
pointer / adjustability / displacement, LITERAL element type -- builds a PACKED unsigned
integer vector with identical element semantics on every backend: a store MASKS to the
element width, a read widens UNSIGNED, a non-integer store is a type error
(interpreter/JVM signal, WASM traps).** Any other `make-array` combination keeps the
general boxed representation.

Rank-n falls back for every specialized element type except packed floats (which pack at
any rank); CHARACTER degrades the same way. Only the REPRESENTATION degrades -- the
general array REMEMBERS the element type, answering `(UNSIGNED-BYTE 8)` from
`array-element-type` and filling with `0` (`.kb/array-literals.md`).

## "Literal" includes a `deftype` alias of one
`LispMacroExpander.resolveElementTypeAlias(spec, closRegistry)` is the one resolver:
strips the quote the compile paths carry, follows alias chains (bounded at 16 hops, which
also terminates `(deftype a () 'a)`), returns its argument UNCHANGED for a non-alias so an
alias-free program compiles to the same bytes. Applies to packed ints, packed floats and
character vectors on all four backends. Sites:
- Interpreter `Environment.makeArrayBuiltin(closRegistry)` -- registered registry-less by
  `createGlobal`, re-registered by `LispEvaluator` with its own registry (the
  `concatenateBuiltin` arrangement: the registry mutates as the program loads, so the
  builtin holds the live object, not a snapshot).
- JVM `JvmArrayCompiler.compileMake`, resolving once up front. The gates
  `JvmLispCompiler.makeArrayIsPackedInt`/`makeArrayIsPackedFloat` resolve too -- trap: a
  gate that missed the alias omits `_iv*`/`_fv*` and silently takes the general path.
- wasm-GC `WasmArrayCompiler.compileMake`; its `packedIntElementWidth` accepts the bare
  list as well as the quote wrapper (it used to demand the wrapper, declining every alias).

**Deliberately unresolved**: a RUN-time designator
(`LispMacroExpander.lowerRuntimeElementTypeMakeArray` tests only character names) and
`with-open-file`/`open`'s `:element-type` (`isBinaryElementTypeLiteral`). Pinned by
`LispEvaluatorTest.makeArrayElementTypeResolvesADeftypeAlias`, its `Jvm`/`Wasm...
IntegrationTest` twins, and the `make-array-element-type-deftype-alias` ci-spec case.

## Representation per backend
- **Interpreter**: `LispIntVector` (`am.ik.rontolisp`) -- `int width` (8/16/32) +
  pre-masked `long[]`; a member of the sealed `LispVal`.
- **JVM**: bare `long[]{width, e0, ...}`; `instanceof long[]` is the free discriminator
  (disjoint from `int[]` charboxes, `double[]`/`float[]`, `Object[]`, `ArrayList`).
  `JvmIntArrayRuntimeBuilder` helpers `_ivAref1`/`_ivAset1`/`_ivDims`/`_ivLength`/
  `_ivToGeneral`/`_ivElementType`/`_ivMake`/`_ivAlike`/`_ivRequireGeneral`, gated on
  `Ctx.usesIntArray` (a `LispIntVector` literal or literal packed `:element-type`; forces
  `usesArrays`; gate off = byte-identical build). Dispatch chains iv -> fv -> general;
  `_ivMake` does the runtime rank check (rank-n -> `_arrayMake`).
- **wasm-GC (Preview 1 and `--component`)**: the BARE
  `TYPE_I8ARR`/`TYPE_I16ARR`/`TYPE_I32ARR` value, `(array (mut i8|i16|i32))`, types 57-59
  in ONE rec group (keeping i32 structurally distinct from `TYPE_LIMBS` under wasm-GC
  canonicalization); no struct wrapper, no dims (`array.len` is the length, rank-1 only);
  `ref.test` discriminates width. `TYPE_IV_SET` (60) is the `_iv_set` signature;
  `--simd`/async/instance blocks shift past all four via `IARR_TYPE_LAST`. `FUNC_IV_SET`
  follows `FUNC_FX_REM` (`FX_FUNC_LAST` includes it; `FUNC_VEC_BASE`/`FUNC_USER_BASE`
  rebase as before).
- **`--no-gc`**: arrays unsupported (eligibility scan's compile error).

**Small-buffer-oriented, not scale-oriented**: the interpreter/JVM `long[]` backing spends
8 bytes per element whatever the declared width (the width is a DISCRIMINATOR, not a
packing scheme); only wasm-GC packs at width. A 1 Mi-element `(unsigned-byte 16)` chunk
decodes at 1.6-4.3 Gelem/s, 1.3-1.7x slower than a real `short[]`
(`.kb/binary-sequence-io.md`) -- fine at that scale, but do not hold a checkpoint-sized
(10^8-10^9 element) buffer whole; stage it in chunks.

## Shared semantics (pinned by tests)
- `aref`/`(setf aref)` rank-1, `row-major-aref`/`%row-major-aset`: masked store returns
  the value AS STORED; out-of-range index errors (WASM: `array.get/set` trap). A
  `TYPE_BIGINT`-tier store contributes its LOW 32 bits everywhere (interpreter
  `BigInteger.longValue()` masking == the wasm `_limb_get(limbs, 0)` arm).
- `length`, `array-dimensions` = `(n)`, `arrayp`/`vectorp`/`%arrayp` true;
  `array-element-type` returns the real `(unsigned-byte N)` list (general arrays: `t`).
- `typep` for `(simple-array (unsigned-byte 8) (*))`-style specs tests `%arrayp` AND
  `(equal (array-element-type x) '(unsigned-byte N))` (dimensions unchecked), so a general
  `#(...)` is never a `(vector (unsigned-byte 8))` -- s-sql's `sql-escape` dispatches on
  that, and it is why a `concatenate` dropping the element type produced a value its own
  result type rejected.
- Printing: a plain `#(1 2 3)` at every width; reading it back yields a general vector
  (conformant). The WASM printer boxes to a general array in place and reuses the general
  renderer.
- `coerce` into an `(unsigned-byte 8|16|32)` vector designator builds packed, same arm as
  `concatenate` (`.kb/concatenate-result-families.md`); `map` and a COMPUTED coerce
  designator do not -- that file carries the trigger.
- `subseq`/`copy-seq` are TYPE-PRESERVING (packed in, same-width packed out): the shared
  `expandSubseqCompat` vector lowering allocates through `%array-alike`
  (`LispNames.ARRAY_ALIKE`, in `CL_INTERNALS`) -- fresh zero-filled, SAME representation
  as its first argument. `replace` mask-stores element-wise into a packed target.
  `concatenate` packs when its RESULT TYPE asks (not the arguments' representation);
  compile paths through the injected `%seq-int-vector` helper, interpreter directly.
  `reverse`, `remove`, `coerce`, `map 'vector` return GENERAL vectors everywhere -- the
  interpreter's `seqResult` deliberately rebuilds general to match the compile backends.
- Fill-pointer surface / `adjust-array` / displacement MUTATORS: "not applicable to a
  packed integer vector" errors (`requireGeneralArray` / `_ivRequireGeneral`). **Unpinned
  edge, mirroring packed floats**: the read-only probes (`array-has-fill-pointer-p`,
  `adjustable-array-p`, the displacement accessors) answer `nil`/`0` on the interpreter
  but error on the JVM and trap on wasm -- keep them out of ci-spec until aligned.
- A packed vector IS a valid `:displaced-to` TARGET everywhere
  (`.kb/adjustable-arrays.md`). The VIEW stays a general array view, but its elements are
  the target's, so a read widens unsigned and a store MASKS. SBCL signals a type error
  where this masks -- this invariant reaching one more surface, not a divergence.
- `equal`/`eql`: identity only. eq-hash: WASM hashes to the default bucket.

## The reader literal
`#N@(...)` (ironclad's array-reader dispatch syntax, `.kb/reader-features.md`) reads a
packed vector for N in {8, 16, 32} (elements masked); any other width reads a plain
`#(...)`. `Token.IntVectorOpen`, `LispReader.readIntVector`. Both compile backends bake it
natively (`WasmQuoteCompiler.compileIntVectorLiteral`,
`JvmQuoteCompiler.compileLiteralIntVector` -- a raw `long[]` build). `PureBuiltinFolder`
is a second producer: it reduces a literal `(coerce '(...) '(vector (unsigned-byte N)))` /
`(make-array ... :initial-contents '(...))` to the same value.

### A long literal is DATA, not a run of `array.set`s
**A packed literal of 16 elements or more goes into static data at the element width, and
the site becomes a copy loop over it: `w/8` bytes an element plus a fixed ~45.** Below 16
the old emission stands (`array.new_default` + one `array.set` per non-zero element,
~12-17 bytes each). Real crossover is ~7 elements; 16 buys margin. Marginal cost 4.0/2.0/
1.0 bytes an element at the three widths
(`WasmLispCompilerTest.aLiteralLookupTableCostsItsOwnBytesAndNotThreeTimesThem`).

Traps:
- **Bytes go through `StringTable.appendShakeableBlob`** -- the ONE active data segment
  the string data occupies (4-byte aligned), registered as a `DroppableDataRange`, so
  `--optimize` cuts a dead table as it cuts a dead string. Hence NOT `array.new_data`:
  that needs a PASSIVE segment and a datacount section, and `WasmTreeShaker` neither
  parses passive segments nor renumbers a dropped `dataidx` -- it throws on the opcode on
  purpose (`scanGc`). Widening the shaker is the prerequisite; it would buy ~35 bytes a
  table.
- **The segment base is an explicit `i32.const`, not the load's memarg offset**: the
  shaker's droppable-range probe reads `i32.const` values out of surviving bodies and
  skips memargs, so a base hidden in a memarg would let it cut a live table.
- **The loop counter is an i64 scratch local, so an ASYNC resume body falls back to the
  `array.set` run** -- that body assembles its own locals declaration and never resolves
  an i64 placeholder (same reason fusion, raw `let` locals and counted `dotimes` stand
  down there).

The array is still allocated and filled at the SITE, so each evaluation yields a fresh,
independently mutable vector -- what `PureBuiltinFolder` rests on
(`.kb/pure-builtin-fold.md`).

## Unboxed fast paths (wasm-GC)
Fused-tree machinery: `.kb/wasm-int-fusion.md`. Packed arms:
- `(aref a i)` inside a fused tree is an `ArefLeaf`: array/index evaluate once into
  scratch locals; the fast path guards `testIntVector` + i31 index and reads
  `array.get_u` -> raw i64 (no `_int_new`, so no `TYPE_BIGNUM` allocation for out-of-i31
  u32 elements); any other shape bails to `WasmArrayCompiler.emitAref1FromSlots` from the
  SAME locals.
- `(setf (aref packed i) <integer tree>)` compiles the value RAW
  (`WasmIntFusionCompiler.tryCompileRaw`) and stores through `_iv_set` (width dispatch +
  wrap truncation); in statement position (`WasmExprCompiler.compileForEffect`) the stored
  value is never materialized -- the hot-loop store allocates NOTHING. Value position
  re-reads and boxes.
- Fused-call defun inlining: a UNIQUELY-defined fixed-arity defun whose single body
  expression is a CLOSED integer-op tree over its parameters (whitelist
  `+ - * mod rem logand logior logxor lognot ash 1+ 1- ldb byte aref`;
  `WasmIntFusionCompiler.isInlinableDefun`, collected in `WasmLispCompiler.compile` into
  `Ctx.inlinableDefuns`, NEVER under `--dynamic`) is substituted into fused trees and
  fused at its direct call sites: a parameter used once takes the argument's tree, one
  used more than once takes it demoted to a shared once-evaluated leaf. Leaf registration
  happens at classify time in source order, preserving argument evaluation order.
  `(ldb (byte s p) x)` with a literal byte spec classifies through its `expandLdb`
  expansion. An asyncMode `rontolisp:async-defun` is excluded even when its rewritten
  plain defun qualifies textually: a call must build the `TYPE_FUTURE` its entry+resume
  state machine answers, and splicing the raw body handed a synchronous caller the value
  instead (`.kb/async-await.md`).

## The heap-type encoding landmine
`WasmWriter.writeHeapType` treated any value >= 0x40 as an abstract heap-type code and
emitted the negative single-byte form, so `ref.test`/`ref.cast`/`ref.null` of type indices
64+ produced "invalid heap type" modules (every async/serve component failed to validate).
The abstract-code boundary is now 0x60 (the real abstract range starts at `exn` 0x69), so
indices up to 95 encode correctly. **Trigger: if a `ref.test`/`ref.cast` target ever
reaches type index 96, replace the int-parameter disambiguation with separate
`writeAbstractHeapType`/`writeTypeIndex` entry points.** Fixed types top out in the 60s
today.

## Status
All four backends implemented, byte-identical on the parity matrix, pinned by the
`packedIntVector*` tests in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` and the `packed-integer-vectors` ci-spec case. Further
speedups live in `.kb/wasm-int-fusion.md` and `.kb/wasm-unboxed-locals.md`.
