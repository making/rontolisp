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
- `--simd` on the JVM: lane kernels carry `double[]`/`float[]` only, so every `vec:` call site asks
  each ARRAY argument POSITIVELY whether it is one of those two
  (`JvmSimdCompiler.emitLaneWidthGuard`; scalar positions are not asked). Trap: asking "is it the
  unsupported one?" lets the next representation fall through to the cast.
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
`BFloat16Test`, `JvmBFloat16ArrayTest`, `LispEvaluatorTest`,
`JvmLispCompilerTest#compileAndRunBfloat16Bits`,
`WasmLispCompilerIntegrationTest#compileAndRunBfloat16Bits`; ci-spec `bfloat16-bits`.
