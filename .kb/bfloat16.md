# bfloat16

`bfloat16` = the TOP SIXTEEN BITS of an IEEE 754 binary32 (sign, f32's 8 exponent bits, 7 mantissa
bits): the storage format published ML checkpoints use. Covers the scalar pair, the rounding the
bulk pair shares with it, and the packed `#bf16` array width (interpreter + JVM only).

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
- **A NaN never changes class**, and **at this width a NaN must never cross a `double` in either
  direction**: `f2d`/`d2f` alike quiet a signalling NaN, losing 126 of the 65536 patterns (sNaN
  `0x7f81..0x7fbf` and negatives), and `f32.demote_f64` may invent any payload. Both sides do the
  bits explicitly (`i64.reinterpret_f64`, `Double.doubleToRawLongBits`). Ordinary values
  deliberately round TWICE (`double` -> f32 -> bfloat16), matching the packed array.

## One rounding, reached three ways
`rontolisp:widen-float-bits` / `narrow-float-bits` share the rounding but reach it by destination
width, because `BFloat16`'s API only takes a `double`.
- `double-float` array: `BFloat16.value` / `BFloat16.bits` directly.
- **`single-float` array never calls `BFloat16` at all**:
  `eval/FloatBitsWidening#bfloat16BitsOfFloat` and `Float.intBitsToFloat(bits << 16)`, duplicated
  in each backend's emitter, no `double` created. The third copy is deliberate.
- All three NaN branches use `payload | ((payload - 1) >>> 31)`. Trap: a plain
  `bits | <quiet bit>` loses the same 126 patterns -- a different way to lose them, not a fix.
- Pins: `LispEvaluatorTest#bfloat16BulkNarrowingIsTheSameRoundingAsTheScalarPair`,
  `JvmLispCompilerTest#compileAndRunBfloat16BulkAgreesWithTheScalarPair`.
- On wasm-GC only the scalar-layout `single-float` cell is exact; the `double-float` arm and both
  `--simd` vblock cells lose the 126 signalling patterns, because `_v_get`/`_v_set` are typed
  `(eq,i32)->f64` at BOTH widths and `WasmFloat16Compiler.emitNarrowLoop` demotes. **That is the
  wasm element model's ceiling, not the pair's** -- `aref` alone loses the identical 126 -- so the
  `--simd` arm needs no f32-native vblock accessors. Keep the exact cell exact (`.kb/vec.md`).

**Two lanes work this file at once**, and both breakages presented as NaN-handling changes that
`git merge` has nothing to say about. Tell the other lane before pushing a NaN change here.

## The packed array
`#bf16(...)` / `(make-array dims :element-type 'bfloat16)` is the third permit of the sealed
`LispFloatArray` -- `LispBFloat16Array(short[] data, int[] dims)`, a fourth EMPTY subtype of
`float` (every `aref` answers a `double`). Interpreter and JVM only; every other backend refuses
it BY NAME at `compiler/UnsupportedFloatWidth`. `vec:` carries the width; `linalg:` declines it at
`%la-etype` / `%la-make`.
- **JVM representation: a bare `short[]` with a TWO-SLOTS-PER-DIMENSION header**
  `[rank, hi_0, lo_0, ..., e_0, ...]`, data offset `1 + 2 * rank` (a `short` caps at 32767).
  `codegen/jvm/JvmPackedFloatWidth` is the ONE place that knows the layout at every width.
- Access goes through the program's own `_bf16Value(I)D` / `_bf16Bits(D)I`, pinned by
  `JvmBFloat16ArrayTest` over ALL 2^32 f32 patterns plus the double NaN space -- exhaustive in the
  NARROW direction on purpose. Element cap: `short[]`, 2^31-1 elements.
- **`--simd` FUSES the decode shape and DECLINES every other pairing** (`.todo/488`). `vec:sum` over
  a bf16 vector, `vec:dot` with a bf16 FIRST operand, and `vec:matvec` / `matvec-into` over a bf16
  matrix run kernels that decode inside the lane loop -- provided every OTHER array operand is
  `single-float`: bf16 weights against f32 activations is the only pairing the plan has
  (`.todo/670`, `.todo/482`) and the only one with a kernel. The product keeps x's width, as the
  defun's does. The contract is an EQUIVALENCE and not a tolerance -- widening is exact, so a fused
  kernel is the f32 kernel over the widened operand BIT FOR BIT -- which is why the width needed no
  entry of its own in the cross-backend identity contract: it joins the f32 reduction contract
  instead, four pinned lanes and all (`.kb/vec.md`, "The lane-count pin"; the bf16 decode's
  `ShortVector.SPECIES_64` is pinned for the same reason `FSPECIES_REDUCE` is).
- **Everything else DECLINES to the scalar defun, and that includes a MIXED bf16/f32 element-wise
  call**, which used to raise the fixed-width error under `--simd` while the defun computed it
  happily -- `--simd` may not turn an answer into an error. Interpreter: `eval/VecSimd`'s `anyBf16`
  guard, asked BEFORE each member's width switch (the mismatch arms signal). JVM:
  `JvmSimdCompiler.emitLaneWidthGuard`'s second arm, keyed on `BF16_OPERAND` -- when the designated
  operand is a `short[]` every other array operand must be a `float[]`, otherwise the ordinary
  two-width test runs over every position. Both arms end at the kernel, so the bridge stays TOTAL:
  no null-check rung, and a call site that never sees the width emits the bytes it always did.
  Trap: every test is POSITIVE -- asking "is it the unsupported one?" lets the next representation
  fall through to the cast.
- The JVM fused kernels read the two-slot header like every other kernel in
  `JvmSimdVectorTemplate`; `bf16Off` / `bf16Dim` are the only two places in it that spell the
  layout. `eval/VecSimdKernels`' mirror takes bare arrays, as its f32 kernels do. Only
  `widenBf16Into` / `narrowBf16Into` are header-free on both sides -- they are bulk buffer
  conversions, not bridge entries.
- The Q8_0 quantized matrix is NOT a fourth width of this umbrella and not a float width at all
  (`.kb/quantized-matrix.md`); `rontolisp:dequantize m 'bfloat16` narrows into this one through
  `BFloat16.bits` / `_bf16Bits`.
- **No element-wise bf16 kernel**: widening is one shift but NARROWING is not vectorized
  (round-to-nearest-even with a NaN guard), so an element-wise arm would be a scalar store loop.
  `.todo/696`.
- **What it costs where it does not pay.** The fused GEMV is BELOW f32 on one thread while the
  matrix is cache-resident and above it once it is not: on a GB10, 1024x1024 loses and 4096x4096
  wins clearly (the numbers, both JITs, in `.todo/488`'s README). The crossover is a cache
  hierarchy and moves with the box. There is no size gate, deliberately: the only BIT-IDENTICAL
  alternative -- widen into an f32 scratch, then the f32 kernel -- is slower at EVERY shape on both
  JITs, so there is nothing to switch to; and the other one, declining to the defun above a size,
  would make the ANSWER depend on the matrix size, which no other backend reproduces. Under
  `--parallel` the arm is at or above parity from 1024x1024 up.
- Printing is `_bf16Print` over `FloatText.bfloat16Text`. A program that `read`s or defines a
  `print-object` method goes through `LispMacroExpander.PRINT_OBJECT_VECTOR_ARM`, whose vector arm
  excludes the packed widths BY NAME -- the miss showed only in an `-o Prog.class` E2E.
- The bulk pair, `read-sequence` and `write-sequence` all still decline bf16
  (`PackedBuffer.of`, `_readSeqPacked`) into the element loop.

## Refusing a width: three behaviours
- **Silent DECLINE** (`null`/`false`, the rung below answers, answer identical): `VecSimd`,
  `LinalgSimd`, `LinalgGpu`, `LinalgBlas` -- guardable by a SOURCE-SHAPE pin
  (`eval/LinalgWidthWireTest`); a reader that GUESSES instead needs a differential test.
- **TEMPORARY refusal**: `LispEvalException` at RUN time, message says "does not yet".
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
(`.kb/format.md`). Nothing calls it yet -- the WASM mirror lands with the array width.

## Tests
`BFloat16Test`, `JvmBFloat16ArrayTest` (the `--simd` section: the fused decode shape equals the
widened-f32 kernel and the interpreter's `--simd`, the declined members equal the defun, and the
lane-count probe), `eval/VecSimdTest` (the interpreter twin, plus the mixed bf16/f32 element-wise
values), `eval/VecSimdBf16KernelsTest` / `codegen/jvm/JvmSimdVectorTemplateBf16Test` (the kernels
themselves), `JvmSimdParallelCompilerTest`, `LispEvaluatorTest`,
`JvmLispCompilerTest#compileAndRunBfloat16Bits`,
`WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits`; ci-spec `bfloat16-bits`.
