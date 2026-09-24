# bfloat16

`bfloat16` = the TOP SIXTEEN BITS of an IEEE 754 binary32 (sign, f32's 8 exponent bits, 7 mantissa
bits): the storage format published ML checkpoints use. Covers the scalar pair, the rounding the
bulk pair shares with it, the packed `#bf16` array width (interpreter + JVM only), and the width's
account -- what it is for, why bf16 and not f16, the name, the prefix and the lattice entry (the
umbrella `.todo/482`, closed 2026-09-08).

## The scalar pair
`rontolisp:bfloat16-bits` (real -> 0..65535) / `rontolisp:bits-bfloat16`. There is no bfloat16
SCALAR, so both cross a `double`. `am.ik.rontolisp.BFloat16` is the single Java authority; both
compile backends emit the arithmetic INLINE (`JvmBFloat16Compiler`, `WasmBFloat16Compiler`)
because `BFloat16` does not travel with a compiled program. Sixteen bits fit an i31, so unlike the
`%ieee754-*` quartet this pair is REAL on all four backends.

## Three invariants
- **Widening is exact and total**: all 65536 patterns narrow back unchanged. Tests assert over all
  65536, never a sample.
- **Narrowing rounds to NEAREST EVEN**: `(f + 0x7fff + ((f >>> 16) & 1)) >>> 16` over the f32 bits.
  A truncating `>>> 16` passes a casual test and biases every sum downward.
- **A signalling NaN cannot exist in a packed SINGLE-FLOAT array at all**, which is what makes
  `NoGcWasmCompiler.compileFloatArrayLiteral`'s `f64.const` + `f32.demote_f64` round trip safe
  rather than lucky: there is no f32 SCALAR, so an element crosses a `double` on the way in and
  on the way out and both crossings quiet it -- `(%ieee754-single-from-bits #x7F800001)` already
  answers `#x7FC00001`. Nor can the `#f(...)` reader syntax produce a NaN of any kind (`nan` is
  not a number token, an overflowing literal is Infinity, and `#.` is not evaluated inside the
  literal); a QUIET NaN reaches the emitter only through `#.` at an ordinary expression position,
  and is the canonical `0x7ff8000000000000`. Checked 2026-09-05, `.todo/487`; pinned by
  `LispEvaluatorTest#evalASignallingNaNCannotSurviveIntoAPackedSingleFloatArray`.
- **A NaN never changes class**, and **at this width a NaN must never cross a `double` in either
  direction**: `f2d`/`d2f` alike quiet a signalling NaN, losing 126 of the 65536 patterns (sNaN
  `0x7f81..0x7fbf` and negatives), and `f32.demote_f64` may invent any payload. Both sides do the
  bits explicitly (`i64.reinterpret_f64`, `Double.doubleToRawLongBits`). Ordinary values
  deliberately round TWICE (`double` -> f32 -> bfloat16), matching the packed array.

## One rounding, reached three ways
`rontolisp:widen-float-bits` / `narrow-float-bits` share the rounding but reach it by destination
width and direction.
- **Narrowing, `double-float` array**: `BFloat16.bits(double)` directly, on every backend that can
  call it (the interpreter; `eval/FloatBitsWidening`).
- **Narrowing, `single-float` array**: `BFloat16.bits(float)` directly on the interpreter too
  (`eval/FloatBitsWidening`, since 2026-09-08, `.todo/746`) -- Java's overload resolution picks the
  `float` overload over `double` without any implicit widening, so no `float` crosses a `double` on
  the way to the authority's NaN handling. The compiled backends still hand-write it INLINE
  (`JvmFloat16RuntimeBuilder#emitBf16Narrow`, and the WASM emitters where the format's source is
  `#bf16`) -- a NECESSARY duplicate, not an avoidable one, because `BFloat16` does not travel with
  a compiled program (`.kb/jvm-export.md`'s "What travels"). Same for the `--simd` fused kernels'
  own narrow (`codegen.jvm.JvmSimdVectorTemplate#floatToBf16`, `eval.VecSimdKernels#floatToBf16`):
  the JVM copy travels with `--simd` output the same way, and the interpreter copy is kept as an
  inline mirror of it on purpose (the two files are tested and read as one operation-for-operation
  pair; folding only one side into a call would make future diffs between them a false negative).
- **Widening, `single-float` array**: `Float.intBitsToFloat(bits << 16)` everywhere, NEVER
  `(float) BFloat16.value(bits)` -- `BFloat16.value` only returns a `double`, and narrowing that
  back to `float` quiets a signalling NaN 126/65536 times, so calling it would be a NaN bug, not
  a redundant round trip. `BFloat16` has no `value(int) -> float` overload (unlike `bits`, which
  gained a `float` overload for exactly this reason on 2026-09-03) because the shift-only widen
  needs no authority call to get right: there is no rounding or NaN branch to keep in sync.
- All NaN branches use `payload | ((payload - 1) >>> 31)` (or the equivalent `u | (((u & 0x7f) - 1)
  >>> 31)` over the already-assembled sign+exponent+payload word `u`). Trap: a plain
  `bits | <quiet bit>` loses the same 126 patterns -- a different way to lose them, not a fix. This
  is the exact bug the `--simd` fused kernels' own narrow carried, unnoticed, from 2026-09-03 (when
  they copied the pre-fix formula) until `.todo/746`'s census on 2026-09-08 -- see "The conversion
  arithmetic census" below.
- Pins: `LispEvaluatorTest#bfloat16BulkNarrowingIsTheSameRoundingAsTheScalarPair`,
  `JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair`,
  `eval.VecSimdBf16KernelsTest#theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern`,
  `codegen.jvm.JvmSimdVectorTemplateBf16Test#theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern`.
- On wasm-GC only the scalar-layout `single-float` cell is exact; the `double-float` arm and both
  `--simd` vblock cells lose the 126 signalling patterns, because `_v_get`/`_v_set` are typed
  `(eq,i32)->f64` at BOTH widths and `WasmFloat16Compiler.emitNarrowLoop` demotes. **That is the
  wasm element model's ceiling, not the pair's** -- `aref` alone loses the identical 126 -- so the
  `--simd` arm needs no f32-native vblock accessors. Keep the exact cell exact (`.kb/vec.md`).

## The conversion arithmetic census (2026-09-08, `.todo/746`)

`.todo/746` was filed on a findings line reading "Seven sites hand-write the bf16 conversion
arithmetic" that named no sites and no owner. A grep for a file touching both `bf16` and 16-bit shift arithmetic answers
twelve files; sorted into what each actually is:

- **The authority**: `BFloat16.java`.
- **Not a duplicate at all -- a caller**: `LispBFloat16Array` (the packed `#bf16` array's
  `aref`/`setf aref`) calls `BFloat16.value`/`BFloat16.bits` directly; it hand-writes nothing.
- **False positives** (matched the grep, hand-write no bf16 arithmetic):
  - `codegen/jvm/JvmPackedFloatWidth` -- knows the packed array's two-slot HEADER layout and calls
    the program's own `_bf16Value`/`_bf16Bits`; the shift arithmetic the grep found is the header
    words (`dims[k] >>> 16`), not the conversion.
  - `codegen/wasm/WasmFloat16Compiler` -- compiles `rontolisp:float16-bits`/`bits-float16`, IEEE
    BINARY16 (5 exponent bits, 10 mantissa), a different format entirely (see the top of this
    file). Matched on "16-bit shift arithmetic" that has nothing to do with bfloat16.
  - `codegen/wasm/NoGcWasmCompiler` -- refuses the packed `#bf16` array BY NAME
    (`compiler.UnsupportedFloatWidth`); it never emits the arithmetic because this backend never
    carries the width at all.
- **Necessary emissions** (a backend writing into an artefact the authority cannot reach -- the
  question is agreement, not existence; `.kb/jvm-export.md`'s "What travels"):
  - `codegen/jvm/JvmBFloat16Compiler` (scalar `bfloat16-bits`/`bits-bfloat16`) -- AGREES, exhaustive
    round-trip pin over all 65536 patterns (`JvmLispCompilerTest#compileAndRunBfloat16Bits`).
  - `codegen/wasm/WasmBFloat16Compiler` (same pair, wasm-GC) -- AGREES, same exhaustive round-trip
    shape (`WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits`) plus the cross-backend
    `bfloat16-bits` case in `ci-spec.yaml`.
  - `codegen/jvm/JvmFloat16RuntimeBuilder#emitBf16Narrow` (bulk `narrow-float-bits`, format
    `:bfloat16`, single-float source) -- AGREES, exhaustive round-trip pin
    (`JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair`, 0/65536 mismatches).
  - `codegen/jvm/JvmFloatArrayRuntimeBuilder` (`_bf16Value`/`_bf16Bits`, the packed `#bf16` array's
    own element access) -- AGREES, the STRONGEST sweep of any site: all 65536 patterns in the widen
    direction and all 2^32 f32 patterns plus the double NaN space in the narrow direction, DIRECTLY
    against `BFloat16` (`JvmBFloat16ArrayTest`).
  - `codegen/jvm/JvmSimdVectorTemplate` (`bf16ToFloat`/`floatToBf16`, the `--simd` fused GEMV/dot/sum
    kernels' decode and the `widenBf16Into`/`narrowBf16Into` bulk buffer conversions) -- widen
    AGREES (an exact shift, no NaN branch to disagree on). Narrow DISAGREED with the authority on
    NaN payloads until this item: `floatToBf16` carried `(bits >>> 16) | 0x0040` (this method's
    original shape, from commit `a345e1a3`, 2026-09-03), which forces the quiet bit unconditionally
    instead of only when the payload was already zero -- the identical bug
    `eval/FloatBitsWidening`'s narrow was fixed for THE SAME DAY (commit `f296ca8e`), which never
    reached this copy. 126/65536 bf16-widened patterns mismatched the authority (measured). Fixed
    2026-09-08 to the `u | (((u & 0x7f) - 1) >>> 31)` shape; pinned by
    `JvmSimdVectorTemplateBf16Test#theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern`
    (all 65536 bf16-widened patterns, direct vs. `BFloat16.bits`, not just round-trip).
  - `runtime/RontoFloatArray` (`bfloat16Value`/`bfloat16Float`/`bfloat16Bits`, the
    `rontolisp:jvm-export` handle's element access at this width; added 2026-09-08, `.todo/689`)
    -- AGREES, the same sweep shape as `JvmFloatArrayRuntimeBuilder`'s: all 65536 patterns
    widening, all 2^32 f32 patterns through BOTH narrow arms plus the double NaN space, directly
    against `BFloat16` (`RontoFloatArrayTest`). It is a necessary emission for the same reason
    the emitted pair is -- `am.ik.rontolisp.runtime` imports NOTHING so that it can travel inside
    someone else's artifact (`.kb/jvm-export.md`, "What travels"), and `BFloat16` does not
    travel. `toFloatArray` widens by the shift alone rather than through `value`, per the
    widening rule above.
- **The avoidable copy, folded**: `eval/FloatBitsWidening#bfloat16BitsOfFloat` -- host Java that
  could call `BFloat16.bits(float)` directly (that overload exists specifically for this call site,
  added 2026-09-03) but reimplemented its body instead, on a stale comment claiming the call would
  auto-widen to `double` (it would not: an exact-type overload beats a widening one). Folded
  2026-09-08 into a direct call; the private method is gone.
- **Kept as a duplicate on purpose, reclassified**: `eval/VecSimdKernels` (`bf16ToFloat`/
  `floatToBf16`, the interpreter's `--simd` mirror of `JvmSimdVectorTemplate`) -- unlike
  `FloatBitsWidening`, this one is host Java that genuinely COULD call `BFloat16.bits` too, so by
  the letter of the rule it is "avoidable". It stays inline anyway: the two `--simd` kernel files
  are documented and tested as one operation-for-operation mirror (`VecSimdBf16KernelsTest`'s class
  javadoc), and the JVM half cannot be folded (see above) -- folding only the interpreter half would
  make the two files diverge in SHAPE while staying equal in VALUE, which is a worse read for zero
  behavioral gain. It carried the same 126-pattern NaN bug as its JVM mirror, from the same commit;
  fixed alongside it, pinned by
  `VecSimdBf16KernelsTest#theNarrowingAgreesWithTheAuthorityOnEveryBf16WidenedPattern`.

**Result: not seven sites, and not twelve.** One authority, one non-duplicate caller, three grep
false positives, six necessary emissions (all agreeing with the authority; one fixed 2026-09-08,
one added 2026-09-08), one avoidable copy (folded), and one duplicate kept deliberately (fixed,
not folded, for the reason above).

The finding this discharges: a fused-kernel NaN bug, unreachable from any `rontolisp:`
primitive today (`narrowBf16Into`/`floatToBf16` have no Lisp-callable call site yet -- `.todo/696`
is what will wire one up), sitting unfixed for five days after its twin was fixed elsewhere. Filed
because the fix was small and the bug was already fully diagnosed by the census; not filed as a
separate item.

**Two lanes work this file at once**, and both breakages presented as NaN-handling changes that
`git merge` has nothing to say about. Tell the other lane before pushing a NaN change here.

## The packed array
`#bf16(...)` / `(make-array dims :element-type 'bfloat16)` is the third permit of the sealed
`LispFloatArray` -- `LispBFloat16Array(short[] data, int[] dims)`, a fourth EMPTY subtype of
`float` (every `aref` answers a `double`). Interpreter and JVM only; every other backend refuses
it BY NAME at `compiler/UnsupportedFloatWidth`. `vec:` and `linalg:` BOTH carry the width -- `linalg:`
declined it at `%la-etype` / `%la-make` until 2026-09-06, because its own width protocol was a boolean
and so admitted exactly two widths (`.todo/687`, `.kb/linalg.md`, "The third width, and the wire it
needed"). No `linalg:` acceleration seam takes it: `--simd`, `--blas` and `--gpu` all decline a
`short[]` operand to the defun, so a `linalg:` answer at this width is the portable one bit for bit.
- **JVM representation: a bare `short[]` with a TWO-SLOTS-PER-DIMENSION header**
  `[rank, hi_0, lo_0, ..., e_0, ...]`, data offset `1 + 2 * rank` (a `short` caps at 32767).
  `codegen/jvm/JvmPackedFloatWidth` is the ONE place that knows the layout at every width --
  except `runtime/RontoFloatArray`, which cannot import it and names the offset itself
  (`BFLOAT16_HEADER_SLOTS_PER_DIM`) for the same reason it hand-writes the conversion pair.
- Access goes through the program's own `_bf16Value(I)D` / `_bf16Bits(D)I`, pinned by
  `JvmBFloat16ArrayTest` over ALL 2^32 f32 patterns plus the double NaN space -- exhaustive in the
  NARROW direction on purpose. Element cap: `short[]`, 2^31-1 elements.
- **The `rontolisp:jvm-export` boundary carries it** since 2026-09-08 (`.todo/689`):
  `RontoFloatArray.Width.BFLOAT16`, `of(short[], int...)` / `toShortArray()` in BIT PATTERNS,
  the same `:float-vector` / `:float-matrix` designators the other two widths cross
  (`.kb/jvm-export.md`, "The packed float array handle"). Before that `checkPacked` refused a
  `short[]` -- a correct refusal, never a wrong number, but a Java caller of a compiled model
  could not pass or receive a bf16 weight matrix.
- **`--simd` FUSES the decode shape and the element-wise narrow pairing, and DECLINES
  every other pairing** (`.todo/488`, `.todo/747`). `vec:sum` over a bf16 vector, `vec:dot`
  with a bf16 FIRST operand, and `vec:matvec` / `matvec-into` over a bf16 matrix run kernels
  that decode inside the lane loop -- provided every OTHER array operand is `single-float`:
  bf16 weights against f32 activations is the pairing the width was built for (`.todo/482`, and
  the plan decision below).
  Beside it, `vec:add` / `sub` / `mul` / `div` (and `vec:+` / `-` / `*` / `/`), `vec:sqrt` /
  `abs` / `negative` / `reciprocal` and their `-into` siblings run element-wise kernels over
  bf16 x bf16 -> bf16: widen both operands inside the lane loop, compute in f32, narrow on
  store (`.todo/747`). The reductions' product keeps x's width, as the defun's does; the
  element-wise result stays in the width, as `vec::%make-like` gives the defun for two bf16
  operands. The contract is an EQUIVALENCE and not a tolerance -- widening is exact, so a
  fused kernel is the f32 kernel over the widened operand BIT FOR BIT, and the f32
  intermediate is the defun's answer bit for bit (swept over all 65536x65536 operand pairs
  per operation for `+ - * /`, all 65536 patterns for the four unary members) -- which is
  why the width needed no entry of its own in the cross-backend identity contract: the
  reductions join the f32 reduction contract instead, four pinned lanes and all
  (`.kb/vec.md`, "The f32-reduction precision contract", whose lane-count pin this is; the
  bf16 decode's `ShortVector.SPECIES_64` is pinned for the same reason `FSPECIES_REDUCE`
  is), while the element-wise kernels are bit-exact at any lane count and run at
  `SPECIES_PREFERRED` like the f32 ones.
- **Everything else DECLINES to the scalar defun, and that includes a MIXED bf16/f32 element-wise
  call**, which used to raise the fixed-width error under `--simd` while the defun computed it
  happily -- `--simd` may not turn an answer into an error. Interpreter: `eval/VecSimd`'s `anyBf16`
  guard, asked BEFORE each member's width switch (the mismatch arms signal); the members with a
  fused element-wise kernel test the all-bf16 pairing POSITIVELY first. JVM:
  `JvmSimdCompiler.emitLaneWidthGuard`'s second arm, keyed on `BF16_OPERAND` -- when the designated
  operand is a `short[]` every other array operand must be a `float[]`, otherwise the ordinary
  two-width test runs over every position -- and its third arm, keyed on `BF16_ELEMENTWISE`:
  when the FIRST operand is a `short[]` every other array operand must be one too, otherwise the
  same ordinary test runs and any `short[]` anywhere declines. Every arm ends at the kernel, so
  the bridge stays TOTAL: no null-check rung, and a call site that never sees the width emits
  the bytes it always did. Trap: every test is POSITIVE -- asking "is it the unsupported one?"
  lets the next representation fall through to the cast.
- **The guard and the bridge are WIDTH-AGNOSTIC; the PAIRING is what the plan restricts.**
  Asked and answered 2026-09-05/2026-09-08 (`.todo/696` part 2), recorded because a later reader
  cannot reconstruct it and both `.todo/490` (bf16 on the device) and `.todo/672` (Q8_0) brush
  against it. Today every fused kernel is narrow WEIGHTS against f32 ACTIVATIONS -- a plan
  decision, not an artefact: the checkpoint umbrella (`.todo/670`, closed 2026-09-10) ruled
  mixed precision out -- `torch:` stays f32/f64, bf16 is a storage width for WEIGHTS and
  nothing in it changes what an activation is -- and `.todo/488`'s table, `.todo/482`'s record and
  `.todo/672`'s Q8_0 rows all measure f32 activations; 490 closed without a pairing, the device
  taking exactly the CPU's). A NARROW x NARROW pairing would be an EXTENSION, not a rewrite:
  - The TOTAL-bridge property does not depend on exactly one operand being narrow. It depends on
    the guard admitting only combinations that HAVE a kernel. `BF16_OPERAND` maps a member to the
    ONE position that may hold a `short[]`; a pairing widens that to the SET of positions that
    may, plus one `instanceof` chain in the bridge entry to pick the kernel. The guard stays a
    pair of exclusive arms both ending at a kernel call, so no call site grows a null-check rung.
  - The KERNEL side is a sibling method per combination (`dotBf16Bf16` beside `dotBf16`), decoding
    both lane groups into the same pinned lanes. One small method per combination -- the C2
    inlining cliff (`.todo/482` round 2) is a rule about method SIZE, and a decoder shared behind
    a flag is what tripped it.
  - The one real cost is the RESULT width: `vec:matvec`'s product follows `x` (`vec::%make-like`),
    so a narrow `x` means a narrow result and a NARROWING store per row -- cheap at one per row,
    unlike the element-wise case. A narrow `-into` destination is the same store.
  This answers the question for `bfloat16` AND for whatever Q8_0 lands as.
- The JVM fused kernels read the two-slot header like every other kernel in
  `JvmSimdVectorTemplate`; `bf16Off` / `bf16Dim` are the only two places in it that spell the
  layout. `eval/VecSimdKernels`' mirror takes bare arrays, as its f32 kernels do. Only
  `widenBf16Into` / `narrowBf16Into` are header-free on both sides -- they are bulk buffer
  conversions, not bridge entries.
- The Q8_0 quantized matrix is NOT a fourth width of this umbrella and not a float width at all
  (`.kb/quantized-matrix.md`); `rontolisp:dequantize m 'bfloat16` narrows into this one through
  `BFloat16.bits` / `_bf16Bits`.
- **`--gpu` takes the SAME pairing on CUDA, and nothing else** (2026-09-06, `.todo/490`):
  `vec:matvec` over a `#bf16` matrix and an `#f` vector runs `gemv_bf16`, which decodes in its
  lane loop and is otherwise `gemv_f32` -- the f32 kernel over the widened matrix bit for bit, the
  equivalence above carried to the device -- at half the bytes a resident row streams (1.9-2.9x
  the f32 kernel where the GEMV is bandwidth-bound; the accumulator became a compensated float
  pair at both widths for it, `.kb/gpu.md`, "The GEMV, and the matrix that stays"). Metal
  declines the width (`GpuDevice.supportsBfloat16()`); every other pairing declines to the rung
  below on either backend. A `short[]` is a residency key like any other host array.
- **The element-wise bf16 kernels, and the measurement that justified them** (`.todo/747`,
  closed 2026-09-10; the reason recorded here until 2026-09-08 was wrong). The claim was:
  widening is one shift but NARROWING is not vectorized (round-to-nearest-even with a NaN
  guard), so an element-wise arm would be a scalar store loop wearing a vector load.
  Measured on 2026-09-08 (`.todo/696`, x64/AVX2, both JITs, numbers and harness in
  `.todo/artefacts/696-the-narrow-width-element-wise-kernels/README.md`):
  - **The narrowing vectorizes.** A branch-free lane form -- the bias-add and odd-bit carry
    as int lanes, the NaN arm as a second expression, a mask choosing between them, an
    `I2S` narrowing store -- is **1.4-3.2x** the scalar `narrowBf16Into` at every size on
    both JITs, and agrees with it on **all 2^32 f32 patterns**. It vectorizes BETTER than
    the widening does: the scalar narrow is compute-bound at 0.7-1.1 Gelem/s, while the
    widen is already memory-bound at one shift per element.
  - **The composite is 2.3-2.7x**: widen both operands, add in f32, narrow on store, against
    the wholly scalar loop -- and above 16 M elements it beats the f32 element-wise kernel
    outright, moving half the bytes.
  - The predicted "vector load in front of a scalar store loop" shape IS worthless
    (0.89-1.07x of plain scalar). The prediction about THAT shape was right; the inference
    that it was the only available shape was not.
  - **And the f32 intermediate is exactly the defun's answer.** An element-wise kernel must
    equal the DEFUN (which reads doubles, computes in f64 and narrows through
    `BFloat16.bits(double)`), not another kernel, so "compute in f32" rounds a third time in
    between. It is harmless, structurally: `bits(double)` itself falls through to
    `bits((float) value)`, and binary64 carries 53 >= 2*24+2 bits, the classical innocuous
    double-rounding condition for `+ - * /`. Theory is not a pin, so the bench sweeps it:
  - **All 65536 x 65536 operand pairs, `add` / `sub` / `mul` / `div`, f32 intermediate
    against the defun's f64: 0 mismatches each.** (`bench.sh` runs this once, before the
    two timing runs; it is JIT-independent and takes about 80 s.) The four unary members
    were swept the same way over all 65536 patterns during `.todo/747` (a scratch sweep,
    not a checked-in bench): `sqrt` / `abs` / `negative` / `reciprocal`, 0 mismatches each.
  The arm's design is the plan decision `.todo/747` took, of the same kind as the GEMV's
  pairing: **bf16 x bf16 -> bf16 only** (a program that chose the width stays in it; the
  result is what `vec::%make-like` gives the defun for two bf16 operands), a mixed pair in
  either direction and a bf16 `-into` destination with wider sources still declining to the
  defun. **The members are exactly the ones with a single-float lane loop** (`add` / `sub`
  / `mul` / `div` with the four CL spellings, `sqrt` / `abs` / `negative` / `reciprocal`,
  all with `-into` siblings): `scale` multiplies by a genuine f64 scalar, the comparison
  selects are scalar loops, and the transcendental ufuncs call `StrictMath` per
  element. Two findings the work turned up, both recorded because a later reader cannot
  reconstruct them: on an **sNaN input** the packed lanes quiet the source payload while
  the scalar instructions answer the indefinite, so the kernels are pinned at `isNaN`
  level there rather than by payload; and the `sqrt` element function is the
  float-domain square root -- NaN on a negative input on both paths (`.kb/vec.md`) -- because CL
  `sqrt` roots negatives into a complex no packed store accepts.
- **What it costs where it does not pay.** The fused GEMV is BELOW f32 on one thread while the
  matrix is cache-resident and above it once it is not: on a GB10, 1024x1024 loses and 4096x4096
  wins clearly (the numbers, both JITs, in `.todo/488`'s README). The crossover is a cache
  hierarchy and moves with the box. **Measured on x64 too** (2026-09-08, `.todo/696`;
  Broadwell AVX2, both JITs, table beside the aarch64 one in the same README): the SHAPE holds
  and the headline reproduces -- 1.63-1.85x at 4096x4096 -- and the cache-resident loss is
  MILDER, 0.88-0.93x at 1024x1024 against the GB10's 0.72-0.84x, with 288x288 at parity or
  ahead. So the no-size-gate decision reads the same on both hierarchies and costs less on the
  x64 one; `identical=true` printed at every shape there too. There is no size gate,
  deliberately: the only BIT-IDENTICAL
  alternative -- widen into an f32 scratch, then the f32 kernel -- is slower at EVERY shape on both
  JITs, so there is nothing to switch to; and the other one, declining to the defun above a size,
  would make the ANSWER depend on the matrix size, which no other backend reproduces. Under
  `--parallel` the arm is at or above parity from 1024x1024 up.
- **The width is documented** since 2026-09-08 (`.todo/696` part 4): the packed array, its
  literal, its interpreter/JVM-only support and its bulk-pattern I/O in
  `doc/{en,ja}/reference/data-types.md`, and the one pairing `--simd` fuses in
  `doc/{en,ja}/guides/simd-acceleration.md` ("The bfloat16 width under `--simd`"). Before that
  only the scalar `bfloat16-bits` / `bits-bfloat16` pair and the `linalg:` side had pages, which
  is why `.todo/488`'s fused kernels landed with `.kb` coverage and no `doc/` change.
- Printing is `_bf16Print` over `FloatText.bfloat16Text`. A program that `read`s or defines a
  `print-object` method goes through `LispMacroExpander.printObjectVectorArm()`, whose
  exclusions are DERIVED from `LispFloatArray.widths()` per permit's own `elementType()` answer
  (2026-09-05) -- spelling them by hand is what let bfloat16 render as a general `#(...)` of
  widened doubles, found only in an `-o Prog.class` E2E.
- **`read-sequence` / `write-sequence` move a bf16 array in ONE bulk transfer** of its STORED
  PATTERNS -- two little-endian bytes an element, which is what a BF16 safetensors or GGUF
  tensor holds -- so such a tensor loads with no conversion at all and writing it back
  reproduces the file byte for byte, signalling NaN payloads included. Deliberately not the
  widened f32: a width that converted on the wire could not round-trip. Interpreter
  `PackedBuffer.of` (width 2, `asShortBuffer`), JVM `_readSeqPacked` / `_writeSeqPacked` (the
  `short[]` arm, data offset `1 + 2 * rank` -- the one arm in that method whose offset is not
  `1 + rank`). Measured 2026-09-05: 2^21 elements round-trip with zero mismatches, 5 ms on the
  JVM and 10 ms on the interpreter.
- **The bulk `widen-float-bits` / `narrow-float-bits` pair carries a bf16 source and
  destination** (2026-09-08, `.todo/745`; interpreter and JVM, the two backends that have the
  width at all). Four arms, and only two of them convert anything:
  - `:bfloat16` <-> `#bf16` is a **straight COPY of the stored patterns** in both directions.
    The patterns already ARE this width's representation, so there is no rounding and nothing
    a NaN can lose -- the same byte-for-byte identity `read-sequence` has, over all 65536
    patterns. An arm that routed the copy through a `float` or a `double` would lose the 126
    signalling ones for no reason at all.
  - `:float16` -> `#bf16` is ONE rounding: the f16 pattern's `float` is exact (binary16 is a
    subset of binary32), so the whole conversion is the narrow, and it is
    `BFloat16.bits(float)` on the interpreter and `JvmFloat16RuntimeBuilder#emitBf16Narrow`
    on the JVM -- the arms that already existed, not a fourth copy of the arithmetic. It is
    the only route a published F16 checkpoint has into the narrow width without allocating
    the f32 array the width exists to avoid.
  - `#bf16` -> `:float16` is the exact shift-widen (never `BFloat16.value`, which only answers
    a `double`) followed by the `Float.floatToFloat16` the single-float arm already runs.
  - Pins, both backends per case, in `JvmBFloat16ArrayTest`:
    `theBulkBitsPairCopiesEveryBf16PatternInBothDirections` (all 65536, the copy identity),
    `theBulkBitsPairConvertsF16AgainstTheRouteThroughAnF32Array` (all 65536 in each
    direction, against the two-step through `#f` that the arm replaces -- which is itself
    pinned against the scalar authority), and `theBulkBitsPairReadsTheBf16HeaderAtEveryShape`
    (rank 2, `:start`, and a 40000 dimension: the two-slot header).
- **`narrow-float-bits` reads the source's TOTAL SIZE from the header's dimension product**,
  at every width and on both backends. The JVM half used to ask `_fvLength`, whose rank-n arm
  goes through `_fvToGeneral` and `_length` -- and `_length` refuses a multidimensional array,
  so a rank-2 source threw `argument is not a sequence` on a compiled program while the
  interpreter (`LispFloatArray.totalSize`) narrowed it happily. Found and fixed 2026-09-08
  working `.todo/745`, at the `single-float` and `double-float` widths as much as at this one;
  pinned by `JvmLispCompilerTest#compileAndRunNarrowFloatBitsReadsARankNSourceLikeTheInterpreter`.
- **`coerce` / `concatenate` reach the width too** (2026-09-06, `.todo/707`):
  `(coerce v '(array bfloat16))` narrows into it on the two backends that carry it, and the
  refusal on the others sits where the representation is chosen -- the shared
  `%seq-float-vector` helper's own `make-array` arm, which wasm-GC already lowers to the
  call-time signal. A guard on `make-array` alone would not have covered it
  ([concatenate-result-families.md](concatenate-result-families.md)).
- **A runtime `:element-type` reaches the width now too.** `(make-array n :element-type et)`
  with `et` a VALUE -- which is what `checkpoint:make-tensor` does, so it is the only way a
  checkpoint reader allocates -- built a boxed general array for `bfloat16` on every backend
  but the interpreter until 2026-09-05, because the dispatch's arm list was transcribed four
  times and every copy spelled six of the seven codes. It is derived from `ArrayElementTypes`
  now: `.kb/array-literals.md`, "A RUNTIME `:element-type`".

## The width's account (the umbrella `.todo/482`, 2026-08-22 -> 2026-09-08)
The umbrella filed the width for ONE goal -- tokens per second on a 1B-class model, where decode
streams every weight once per token, so the width IS the bandwidth -- and closed with its eight
children (`483`-`490`) and the remainder lane (`746`, `745`, `689`, `696`, `732`) done. The
measurement record is `.todo/artefacts/482-bfloat16-a-narrow-width-that-pays/README.md` (three
rounds: the spike, both JITs plus the quantized widths, x64). What it decided, and why:
- **bfloat16 and not IEEE binary16**, although `Float.floatToFloat16` is in the JDK. Same GEMV,
  4 accumulators + FMA, 4096x4096: f16 0.60x of f32, bf16 **1.60x**; cache-resident 1024x1024,
  0.32x / 0.88x. bf16 IS the top half of an f32, so the decode is one shift and the halved bytes
  become the bandwidth win; f16 needs a ~6-op lane trick (the Vector API has no half-precision
  species) and never reaches the memory wall -- on either JIT, on aarch64 and on x64. Three more:
  every current checkpoint is published in bf16; widening is EXACT, so a fused kernel is the f32
  kernel over the widened operand bit for bit (why it could join the f32 reduction contract
  instead of needing its own); bf16 keeps f32's exponent range where f16 underflowed N(0, 0.02)
  weights to zero. f16 is a load-time conversion instead (`.todo/671`, `.kb/checkpoint-readers.md`).
- **Memory was never the constraint** at this size: 4.4 GB of f32 fits an 8 GB laptop. What the
  width buys at 1B is tok/s (1.6-2.0x measured, one thread and twenty -- conditional on the
  accumulator count, README section 7) and, on the device, the residency cap (`.kb/gpu.md`).
- **The name is `bfloat16`, not `short-float`.** `short-float` means an IEEE-ish narrow float in
  every other Lisp and stays what CL lets it be here -- an alias of the one float format
  (`.kb/declarations-type-checks.md`) -- and free for an IEEE f16 width if one is ever wanted;
  `bfloat16` is what C++23, PyTorch, JAX and `ml_dtypes` call it. Java spells it `BFloat16`
  (`LispBFloat16Array`, `LispNames.BFLOAT16`, `FloatText.bfloat16Text`); prose may say "bf16" for
  the bits, never the code.
- **In the type lattice it is an EDGE below `float`, not a fifth alias of it** (fixed 2026-09-08
  at the umbrella's close): an EMPTY type, since no scalar has it, so `(subtypep 'bfloat16 'float)`
  is T, `(subtypep 'float 'bfloat16)` NIL and `(typep 1.0 'bfloat16)` NIL -- literal or computed,
  on all four backends (ci-spec `bfloat16-type-lattice`). Collapsed, the reverse direction
  answered T and a COMPUTED pair answered NIL against everything on the compile paths, because
  the runtime universe derives edges and hand-lists aliases (`.kb/declarations-type-checks.md`).
- **The prefix `#bf16(` is a WIDTH TAG, not a letter.** `#b(` reads as the binary radix, and the
  single-letter space (`#f(`, `#d(`) runs out at the next narrow width; the tag scheme has room --
  `#f16(`, `#fp8(` -- in the vocabulary numpy, PyTorch and C++23 use. Lexing is the `#S(` / `#P"` /
  `#f(` shape (a fixed prefix that must be followed by the delimiter, else symbol reading) with one
  ORDER requirement: the `#bf16(` branch runs BEFORE the `#x`/`#o`/`#b` radix branch, which would
  otherwise claim the `#b` (`f` is not a binary digit, so it would fail rather than mis-read, but
  the order is what keeps it so; `LispLexer`, pinned by `LispFloatArrayTest` reading `#b1010` and
  `#bf16(1.0)` in one program). `#f32(` / `#f64(` aliases of `#f(` / `#d(` were left out on
  purpose: four runtime readers would carry them for a symmetry no program asks for.
- **Where each piece landed**, for the history rows: `483` the exhaustive width switches, `484` the
  interpreter array, `485` the JVM array and its two-slot header, `486` the refusals, `487`
  conversion and the reader path, `488` the fused kernels, `489` the 1B model, `490` the device;
  then `707` `coerce`/`concatenate`, `687` `linalg:`, `745` the bulk pair, `746` the census, `689`
  `jvm-export`, `696` the element-wise measurement, `747` the element-wise kernels that
  measurement justified, and `480` the GEMV's accumulator count, on which the one-thread
  1.6x turned out to depend -- all three closed 2026-09-10. **The 1.6x has ARRIVED and waits
  on nothing**: `matvecRowsBf16` carries `MATVEC_ACCUMULATORS` and `MATVEC_ACC_THRESHOLD`
  with the f32 arm (by contract -- fused equals widen-then-f32-kernel bit for bit, so the two
  arms cannot carry different counts), and `.todo/488`'s README withdrew its 0.80x / 1.02x
  parity tables for 1.32-2.00x at 4096x4096 on the GB10 and 1.63-1.85x on x64.

## Refusing a width: three behaviours
- **Silent DECLINE** (`null`/`false`, the rung below answers, answer identical): `VecSimd`,
  `LinalgSimd`, `LinalgGpu`, `LinalgBlas` -- guardable by a SOURCE-SHAPE pin
  (`eval/LinalgWidthWireTest`); a reader that GUESSES instead needs a differential test.
- **TEMPORARY refusal**: `LispEvalException` at RUN time, message says "does not yet". No
  `bfloat16` site is in this state any more -- the bulk pair was the last one and it landed
  2026-09-08 (`.todo/745`), so a "does not yet" mentioning this width in the tree today is a
  stale comment rather than a scheduled arm.
- **PERMANENT refusal**: `UnsupportedOperationException` on the COMPILE path naming width and
  backend, built by `compiler/UnsupportedFloatWidth`, positioned by `SourceProvenance.noteFailure`.
  Not `LispCompileException`: `codegen/wasm` does not use it.
- **The phase and the exception TYPE carry the distinction; the prose only decorates it.** Pinned
  by `WasmLispCompilerTest` / `NoGcWasmCompilerTest` on the exact text and by a test asserting
  every "does not yet" message is a `LispEvalException`. Refusals READ a width designator, never a
  boolean or an int code with a `default:` arm.

## Printing
`FloatText.bfloat16Text` = the shortest decimal that reads back as the same bfloat16 (`singleText`
would print the widened f32's digits). It walks significant-digit counts upwards and hands the
first that narrows back to `singleText`, keeping the plain-versus-exponent decision shared
(`.kb/format.md`). Its callers are `LispBFloat16Array`'s element print and the JVM's `_bf16Print`;
there is no WASM mirror because no WASM backend carries the width.

## Tests
`BFloat16Test`, `JvmBFloat16ArrayTest` (the `--simd` section: the fused decode shape equals the
widened-f32 kernel and the interpreter's `--simd`, the fused element-wise kernels equal the
defun on all three legs, the declined members equal the defun, and the lane-count probe),
`eval/VecSimdTest` (the interpreter twin: the fused element-wise kernels equal the defun, plus
the mixed bf16/f32 element-wise values), `eval/VecSimdBf16KernelsTest` /
`codegen/jvm/JvmSimdVectorTemplateBf16Test` (the kernels themselves: each new kernel against
the scalar composite on both sides of the lane gate and on tricky patterns, the `-into`
siblings against the allocating ones, aliasing, the sNaN class pin), `JvmSimdParallelCompilerTest`,
`LispEvaluatorTest`, `JvmLispCompilerTest#compileAndRunBfloat16Bits`,
`WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits`; ci-spec `bfloat16-bits`,
`bfloat16-packed-array` (the fused element-wise kernels over 300 elements on the `--simd` legs).
