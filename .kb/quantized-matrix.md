# The quantized matrix: ggml's Q8_0 as a first-class weight matrix

**Invariant: `rontolisp:quantized-matrix` holds a ggml `Q8_0` tensor's bytes VERBATIM, is
immutable and dequantizes on read, and `vec:matvec` over it computes ggml's integer-dot
shape with a result that is the scalar `vec.lisp` defun's BIT FOR BIT on every backend
that carries the type, with or without `--simd` / `--parallel` / `--gpu`.** Interpreter and
JVM; both WASM backends refuse it by name. `.todo/672`; the device kernel `.todo/728`.

## The type
- Root `LispQuantizedMatrix(QuantizedFormat format, int[] dims, byte[] blocks)`, a permit of
  `LispVal`, NOT of the sealed `LispFloatArray`: an element has no slot of its own (`value =
  q * scale[i / 32]`), so a float-width arm would be one every element-wise kernel and
  `(setf aref)` can only refuse. Rank 1 or 2 (rank 1 = one row); the last dimension is a
  multiple of 32. `QuantizedFormat` is the enum a `q4-k` joins; switch over it as an
  EXPRESSION.
- **Storage is the ggml block layout on BOTH backends**: per block one binary16 `d` (little-
  endian) then 32 int8 quants, 34 bytes, row-major. `read-sequence` / `write-sequence`
  therefore move a GGUF tensor as ONE transfer (`PackedBuffer` width 1, JVM `_readSeqPacked`'s
  `byte[]` arm) and a written matrix is what `llama.cpp` reads; `:start`/`:end` count bytes.
- **The `byte[]` is load-bearing.** The packed integer vector stored one byte in eight when this
  type arrived (`.todo/672`), which would have made it twice the f32 matrix it exists to shrink.
  On the JVM the `byte[]` is SHARED with the `(unsigned-byte 8)` vector since 2026-09-26
  (`byte[]{8, e0, ...}`, `.kb/packed-integer-vectors.md`): where both can exist, slot 0 tells them
  apart -- this header's format code (1) against the octet vector's width tag (8) -- so **no format
  code may be 8**, and every `instanceof byte[]` door here reads it (`emitMatrixTest`,
  `emitQuantizedArm`, the `--simd` lane guard, `JvmGpuTemplate.gpuMatvec`).
- JVM representation: `[format:int LE][rank:int][dim_k:int...]` then the blocks, data offset
  `8 + 4 * rank`. Three places spell it: `JvmQuantizedMatrixRuntimeBuilder` (the `_qm*`
  helpers), `JvmSimdVectorTemplate.qmOff/qmDim` and `JvmGpuTemplate.qmOff/qmDim` (the
  `--gpu` bridge's arm). Ints, so no 32767 cap (`JvmQuantizedMatrixTest`, the 40000-row and
  40000-column shapes).
- Surface: `aref`/`row-major-aref` answer `q * d` as a double (exact: 8 bits x 11 bits);
  `(setf aref)` signals "immutable"; `array-dimensions`/`-rank`/`-total-size`/`-dimension`
  work; `array-element-type` answers the format symbol `Q8-0` (what `vec.lisp` and
  `linalg.lisp` dispatch on); `arrayp`/`vectorp`/`(typep x 'array)` are nil;
  `rontolisp:quantized-matrix-p`, `(typep x 'rontolisp:quantized-matrix)`, `typecase` and
  `type-of` (`QUANTIZED-MATRIX`) know it. `length` at rank 1 only. Prints
  `#<quantized-matrix q8-0 (rows cols)>`; no literal, deliberately.
- Constructors: `rontolisp:quantize` (any packed float source, narrowed to f32 first),
  `rontolisp:make-quantized-matrix` (zero blocks, the `read-sequence` destination), and
  `gguf.lisp`'s type-8 arm (make + one `read-sequence`, `:element-type` ignored). Every
  spelling of the designator names the format by its LOCAL name: `'q8-0`, `'rontolisp:q8-0`,
  `:q8-0` (`QuantizedFormat.ofSymbolName`, `_qmLocalName`).
- **`rontolisp:quantized-rows m rows` is the type's `subseq`** (`.todo/732`): a fresh RANK-2
  matrix whose row `i` is row `(nth i rows)` of `m`, for a list of row indexes. A row is
  whole blocks (`cols / 32 * 34` bytes), so the gather MOVES THE BLOCKS -- one
  `System.arraycopy` a row, the result's bytes the source's, no round trip through a float
  array. The source may be rank 1 (one row); an empty list answers a matrix of no rows; an
  index outside the source and anything but a list of integers signal. It is what lets a
  program SPLIT a quantized weight matrix -- `examples/llm`'s `split-gated-q`, whose halves
  interleave head by head, is one call a half -- without the scratch file it used until
  2026-09-08 and without naming `dequantize` / `quantize`, which a program that also
  compiles to WASM cannot (below).
- `rontolisp:dequantize m 'single-float|'double-float|'bfloat16` -> a fresh packed array.
  `linalg::%la-etype` maps `q8-0` to `single-float`, so `linalg:row` over a quantized
  embedding table answers the `#f` row the rest of a decode step expects (the `Q8_0` file
  of Qwen3.5 ran through `llm.lisp` unchanged once that line was in).

## `quantize` is `quantize_row_q8_0_ref`, byte for byte
f32 absmax per block (a NaN never raises it: strict `>`), `d = amax / 127f`, `id = 1f / d`
(0 when `d` is), `d` -> binary16 nearest even (`Float.floatToFloat16`), quant =
`roundf(x * id)` -- ties AWAY from zero, `x < 0 ? -Math.round(-x) : Math.round(x)`
(`Math.round` alone sends -2.5 to -2). `eval.QuantizedMatrices.quantizeRowQ8_0` and the
JVM's `_qmQuantizeBlocks` are the two copies; `QuantizedMatrixTest.quantizeProducesGgmlsBytes`
pins the bytes against a second transcription. NOT `quantize_row_q8_0` (the arch-specific
activation quantizer), which rounds ties to even -- the file-writing path is the `_ref`.
A dequantized value is within `amax / 254 + amax * 2^-11` of the source (half a quant plus
the binary16 rounding of the scale).

## The GEMV contract: defun == kernel, bit for bit
`vec::%matvec-quantized` (the defun, spliced on every backend as a dead arm where the type
does not exist) and the kernels `VecSimdKernels.matvecQ8F/D` /
`JvmSimdVectorTemplate.matvecQ8F/D` do, in this order:
1. Quantize the activation per block of 32: `amax` over `(abs x)` with a strict `>`,
   `sx = amax / 127` IN DOUBLE, `q = (round (/ x sx))` -- CL `round` = `Math.rint`, ties to
   even -- and `0` when `sx` is 0. Never f32 arithmetic here: the defun has none.
2. Per row, per block: FOUR exact integer lane sums -- lane `i` over the block's columns
   `j` with `j mod 4 = i` (lanes: the activation quantized into a `short[]` once per GEMV;
   four `ByteVector.SPECIES_64` weight loads each widened `B2S` into
   `ShortVector.SPECIES_128` as PART 0, short multiply against the activation's shorts,
   short add of two eight-column groups -- `|2 x 128 x 127| = 32512 < 32767`, which is why
   the activation is clipped to +-127 and never -128 -- so short lane `k` holds columns `k`
   and `k + 8`; then `S2I` into `IntVector.SPECIES_128` as part 0 of the sum and of the sum
   with its halves swapped by a constant `rearrange`, int adds; the defun four `s0..s3` over
   `j = base + 4k + i`). **No part-1 conversion anywhere**: it is a `slice`, and C2 compiles
   `slice` as the Java it is (`.kb/vec.md`, the third JIT cliff; `.todo/706`) -- then per
   lane ONE f32 multiply-add: the lane sum to f32 (`convert(I2F, 0)`,
   exact below 2^24) times `p = (float) (sw * sx)`, `sw` the binary16 scale widened, added
   into one `FloatVector.SPECIES_128` accumulator. No FMA: two roundings on both sides, and
   the defun has no fused form.
3. Per row, fold `(acc0 + acc2) + (acc1 + acc3)` in f32 and store narrowed or widened to the
   result width (`vec::%make-like` follows `x`: `#f` x -> `#f`, `#d` -> `#d`; `-into` writes
   the destination given).
**The defun spells every f32 step as a double operation narrowed once through a
single-float cell (`vec::%f32`), and that IS the f32 operation: 53 >= 2 * 24 + 2**, the
innocuous-double-rounding bound, so a product or a sum of two f32 values rounded once from
double is correctly rounded -- the emap rule of `.kb/vec.md`, applied to a reduction.
**The four lanes and the f32 width are the pinned part**, as `FSPECIES_REDUCE` is for `#f`:
`IntVector.SPECIES_128` / `FloatVector.SPECIES_128` are fixed and a host with wider vectors
must not widen them. No threshold, no other accumulator split: a block is the unit and an
integer sum does not depend on the fold. Still a STRONGER contract than `#f`'s: the flag
cannot change a bit, so tests assert equality, and `ci-spec`'s standalone `quantized-matrix`
case prints the product. **Two shapes were built and rejected first** (2026-09-05, the
README): one `reduceLanes` per block plus a scalar double chain, latency-bound at 5-6 Gelem/s
on one thread whatever the shape; and double lanes through `convertShape(I2D)`, which Graal
25 does not intrinsify -- 0.02 Gelem/s (`.kb/vec.md`, the second JIT cliff). **A third shipped
and was replaced** (2026-09-06, `.todo/706`): 128-bit byte loads widened through part-1
conversions, the same bits as today's kernel, 0.7x of f32 under C2 because each part-1
conversion is a `slice`; it is kept in `Q8GemvBench` as the probe row, the reduce-per-block
shape's numbers standing in `.todo/672`'s README. A kernel here must also fit C2's
`NodeCountInliningCutoff`: two rows a pass ran boxed (0.1x) with no warning. **Trap**: a
NaN activation is `round`'s error on the defun
and 0 in the kernel -- finite inputs only, as for every `vec:` member.
- Bit-identity is between OUR defun and OUR kernels. ggml's `Q8_0 x Q8_0` kernel quantizes
  the activation in f32 and folds in f32 in its own order, so the two implementations agree
  on the ARGMAX most of the time and not on the bits (below).
- **The device kernel is the same bits** (`--gpu`, CUDA only, `.todo/728`; `.kb/gpu.md`, "The
  GEMV, and the matrix that stays"): `gemm.cu`'s `gemv_q8_0` takes a rank-2 matrix against
  an `#f` vector -- the f32 pairing -- with the activation quantized on the host by step 1
  (`Gpu.quantizeActivationQ8`, the THIRD transcription of that rule beside
  `VecSimdKernels.quantizeActivationF` and `JvmSimdVectorTemplate`'s, pinned block for block
  in `GpuDeclineTest`) and uploaded in place of the f32 vector, eight threads a row each
  owning one lane's columns of a block, the four f32 chains walked in block order with
  `__fmul_rn` / `__fadd_rn`, the fold by two shuffles. Pinned as raw-bit EQUALITY on every
  row against a transcription of steps 1-3 (`GpuTest`) and as equal program output under
  every flag combination (`LinalgGpuTest`, `JvmLinalgGpuAccelCompilerTest`); `examples/llm`
  over the Q8_0 GGUF prints the same 256 tokens with the flag on and off. A `#d` vector,
  rank 1 and Metal decline to the rung below, never signal. What it buys: 13.9 ms a forward
  against 16.7 at bf16 on the GB10 (1.20x), the device streaming the 34-byte blocks at 190
  GB/s.
- Interpreter chain: `VecSimd` answers a `LispQuantizedMatrix` in `matvec`/`matvec-into`
  BEFORE `array(...)`, declining any pairing without a kernel (rank 1, mixed destination
  width, a short x) to the defun; every other member hands a quantized argument to the
  defun. `LinalgBlas` declines it by its `instanceof LispFloatArray` guard; `LinalgGpu.matvec`
  takes the rank-2-against-`#f` pairing ahead of the lane kernel (`matvecQuantized`).
  `PackedBuffer.load` reports a `read-sequence` into the blocks to the write hook, as every
  bulk write does, so a re-read matrix is a first sight again on the device.
- JVM chain: `JvmSimdCompiler.emitLaneWidthGuard`'s FIRST arm, `QUANTIZED_OPERAND` (matvec
  0, matvec-into 1): weight `byte[]` and the other array operands all `float[]` or all
  `double[]`, else fallback to the defun; then the bf16 arm, then the two-width test. The
  bridge stays total. `compileMatvecChain`'s device rung takes the allocating form's
  `byte[]` against a `float[]` ahead of that arm (`JvmGpuTemplate.gpuMatvecQ8`); the library
  rung declines a `byte[]`. Under `--gpu` the compiled `rontolisp:quantize` reads its source
  through `_gpuMaterialize` first -- a device result's host array is a stub
  (`JvmQuantizedMatrixCompiler`; found by the reader corpus of
  `JvmLinalgGpuAccelCompilerTest`, which now has a `quantize` line).
- `--parallel` splits rows as for every GEMV; the activation is quantized once, before.

## The gate on the JVM
`JvmLispCompiler`: `usesQuantized` = the program names `rontolisp:quantize` or
`rontolisp:make-quantized-matrix` -- the two names that build a matrix out of nothing;
`quantized-rows` needs one to exist already and so does NOT open the gate (the pruner keeps `gguf::%read-tensor` only for a
`gguf:read` program). On: `_qm*` helpers, the `byte[]` arms of every `_fv*` helper
(`JvmFloatArrayRuntimeBuilder.emitQuantizedArm`), the `_readSeqPacked` arm, the print branch,
and `usesFloatArray` forced on. Off: `dequantize`, `quantized-rows` and the two `%quantized-*` accessors
compile to a call-time signal, `quantized-matrix-p` to `(progn x nil)`, and the class is
byte-identical to one that never knew the type -- which is what lets `vec.lisp`'s dead arm
and the prelude's `type-of` clause compile everywhere. `BuiltinFunctionWrappers` gates the
four wrappers on the reference for the same reason.

## Refusals (`.kb/bfloat16.md`'s three behaviours)
- wasm-GC: `quantize`/`dequantize` PERMANENT at compile time
  (`UnsupportedFloatWidth.refuseQuantized`); `make-quantized-matrix`, `quantized-rows` and
  the two accessors a CALL-TIME signal with the same sentence (a spliced library's dead arm);
  `quantized-matrix-p` -> `(progn x nil)`. `--no-gc`: all five names refused at compile time.
  Pinned by `WasmLispCompilerTest` / `NoGcWasmCompilerTest`; ci-spec `refusedOn`. A program
  that compiles to WASM as well (`examples/llm`) therefore cannot NAME `quantize` /
  `dequantize` even in a guarded arm -- its Q8_0 split is a `quantized-rows` gather a half
  (`split-gated-q-blocks`; until 2026-09-08 a byte copy through a scratch file in `$TMPDIR`,
  when `file-position` could not seek yet).
- `--blas`: silent decline. `--gpu`: the rank-2-against-`#f` GEMV is taken on CUDA (the
  defun's bits, above); every other pairing and Metal decline silently.
  `rontolisp:jvm-export`: not a boundary type.

## What it costs (2026-09-06, GB10, `.todo/706-.../README.md`)
One thread, 4096x4096, against the shipped f32 GEMV, two passes of the harness: **Graal
1.42-1.57x** (12.6-12.9 Gelem/s; bf16 1.31-1.45x), **C2 1.72-1.91x** (14.6-14.8 Gelem/s;
bf16 1.76-2.00x). Instruction-bound, not bandwidth-bound, on both JITs: the Vector API has
no int8 dot-product instruction, so a block is a widen-multiply-widen chain of ~25
instructions plus a ~10-instruction scalar scale chain where ggml's NEON kernel spends two
`SDOT`s and a scale; Graal runs it at ~9 cycles a block (6.5 the integer part, 3.5 the
scale, measured apart) and C2 slightly faster. 5632x2048 Graal 1.31-1.45x / C2 1.38-1.68x;
the cache-resident shapes lose on both (1024x1024 0.67-0.78x, 288x288 0.42-0.6x). The
quarter-size bytes pay where bandwidth is the limit: `--parallel` (20 threads) 2.2-3.3x f32
under Graal (91-120 Gelem/s, past the f32 arm's ~40 Gelem/s memory wall) and 1.6-2.5x under
C2 -- a direction, not a rate; that column moves 0.5x between passes (`.todo/702`). Until
2026-09-06 the C2 column read 0.70-0.77x, slower than f32 on a stock OpenJDK: the kernel's
part-1 conversions were each a `slice` under C2 (`.todo/706`; the 2026-09-05 record, with
the item's 2.00x premise corrected, is `.todo/672-.../README.md`). No size gate, for
`.todo/488`'s reason. Relative error against f64: Q8_0 7.5e-3 .. 7.8e-3, bf16 1.6e-3, f32
2e-7.

## Against llama.cpp (2026-09-05, GB10)
Raw completion of `"Once upon a time"` -- four token ids `12162 5028 264 854`, no BOS, no
template, identical on both sides by construction -- at temperature 0 over ggml-org's
`Qwen3.5-0.8B` GGUFs, `llama.cpp 0eadefebd` CPU/NEON against `examples/llm/llm.lisp
--simd --parallel` (the build at this item's close): BF16 file token-identical over 64
generated tokens; Q8_0 file identical for 60 tokens then a different word (`llama.cpp`'s
Q8_0 output equals its own BF16 output over all 64). Raw rather than chat because the chat
template rendering is the component twice shown to differ on the Qwen family, so what is
left to differ is the arithmetic -- and at Q8_0 the arithmetic is two different kernels.
Record and ids: `.todo/672-.../README.md`.

## Tests
`eval/QuantizedMatrixTest` (surface, ggml bytes, dequantize bound, bulk transfer, the
gather's bytes, defun ==
`--simd` == `--parallel` at both widths, the declines, `linalg:row`), `eval/VecSimdQ8KernelsTest`
and `codegen/jvm/JvmSimdVectorTemplateQ8Test` (kernels against the defun transcribed, bits),
`codegen/jvm/JvmQuantizedMatrixTest` (both backends, the header past 32767, the gate),
`GgufLibraryTest` (the synthetic Q8_0 tensor), ci-spec standalone `quantized-matrix`. The
device: `am/ik/gpu/GpuTest` (the kernel against the contract transcribed, every row's bits,
the two-sight rule, offsets, the leak run), `am/ik/gpu/GpuDeclineTest` (the host quantizer
block for block, the declines, the PTX entry -- every machine), `eval/LinalgGpuTest`,
`codegen/jvm/JvmLinalgGpuAccelCompilerTest` and `GpuOfferDifferentialTest` (the interceptors:
equal output under every flag, the residency hit / resident bytes as the proof it ran).
Bench: `eval/Q8GemvBench`, `codegen/jvm/Q8TemplateGemvBench`, `.todo/672-.../bench.sh`; the
device kernel's shapes `.todo/artefacts/123-gpu-acceleration/Q8ExactKernelProbe.java`.
