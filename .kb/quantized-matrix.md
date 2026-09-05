# The quantized matrix: ggml's Q8_0 as a first-class weight matrix

**Invariant: `rontolisp:quantized-matrix` holds a ggml `Q8_0` tensor's bytes VERBATIM, is
immutable and dequantizes on read, and `vec:matvec` over it computes ggml's integer-dot
shape with a result that is the scalar `vec.lisp` defun's BIT FOR BIT on every backend
that carries the type, with or without `--simd` / `--parallel`.** Interpreter and JVM;
both WASM backends refuse it by name. `.todo/672`.

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
- **The `byte[]` is load-bearing.** The packed integer vector stores one byte in eight
  (`.kb/packed-integer-vectors.md`), which would make this type twice the f32 matrix it
  exists to shrink. On the JVM a bare `byte[]` is also the free `instanceof` discriminator.
- JVM representation: `[format:int LE][rank:int][dim_k:int...]` then the blocks, data offset
  `8 + 4 * rank`. Two places spell it: `JvmQuantizedMatrixRuntimeBuilder` (the `_qm*`
  helpers) and `JvmSimdVectorTemplate.qmOff/qmDim`. Ints, so no 32767 cap
  (`JvmQuantizedMatrixTest`, the 40000-row and 40000-column shapes).
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
- `rontolisp:dequantize m 'single-float|'double-float|'bfloat16` -> a fresh packed array.
  `linalg::%la-etype` maps `q8-0` to `single-float`, so `linalg:row` over a quantized
  embedding table answers the `#f` row the rest of a decode step expects (the `Q8_0` file
  of Qwen3.5 ran through `llama2.lisp` unchanged once that line was in).

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
   `j` with `j mod 4 = i` (lanes: two `ByteVector.SPECIES_128` loads each side, `B2S` into
   `ShortVector.SPECIES_128`, short multiply, short add of the two halves -- `|2 x 128 x
   127| = 32512 < 32767`, which is why the activation is clipped to +-127 and never -128 --
   `S2I` into `IntVector.SPECIES_128`, int adds; the defun four `s0..s3` over `j = base + 4k
   + i`) -- then per lane ONE f32 multiply-add: the lane sum to f32 (`convert(I2F, 0)`,
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
25 does not intrinsify -- 0.02 Gelem/s (`.kb/vec.md`, the second JIT cliff). The first is
kept in `Q8GemvBench` as a probe. **Trap**: a NaN activation is `round`'s error on the defun
and 0 in the kernel -- finite inputs only, as for every `vec:` member.
- Bit-identity is between OUR defun and OUR kernels. ggml's `Q8_0 x Q8_0` kernel quantizes
  the activation in f32 and folds in f32 in its own order, so the two implementations agree
  on the ARGMAX most of the time and not on the bits (below).
- Interpreter chain: `VecSimd` answers a `LispQuantizedMatrix` in `matvec`/`matvec-into`
  BEFORE `array(...)`, declining any pairing without a kernel (rank 1, mixed destination
  width, a short x) to the defun; every other member hands a quantized argument to the
  defun. `LinalgBlas`/`LinalgGpu` decline it by their `instanceof LispFloatArray` guards.
- JVM chain: `JvmSimdCompiler.emitLaneWidthGuard`'s FIRST arm, `QUANTIZED_OPERAND` (matvec
  0, matvec-into 1): weight `byte[]` and the other array operands all `float[]` or all
  `double[]`, else fallback to the defun; then the bf16 arm, then the two-width test. The
  bridge stays total. `compileMatvecChain`'s device/library rungs decline a `byte[]`.
- `--parallel` splits rows as for every GEMV; the activation is quantized once, before.

## The gate on the JVM
`JvmLispCompiler`: `usesQuantized` = the program names `rontolisp:quantize` or
`rontolisp:make-quantized-matrix` (the pruner keeps `gguf::%read-tensor` only for a
`gguf:read` program). On: `_qm*` helpers, the `byte[]` arms of every `_fv*` helper
(`JvmFloatArrayRuntimeBuilder.emitQuantizedArm`), the `_readSeqPacked` arm, the print branch,
and `usesFloatArray` forced on. Off: `dequantize` and the two `%quantized-*` accessors
compile to a call-time signal, `quantized-matrix-p` to `(progn x nil)`, and the class is
byte-identical to one that never knew the type -- which is what lets `vec.lisp`'s dead arm
and the prelude's `type-of` clause compile everywhere. `BuiltinFunctionWrappers` gates the
four wrappers on the reference for the same reason.

## Refusals (`.kb/bfloat16.md`'s three behaviours)
- wasm-GC: `quantize`/`dequantize` PERMANENT at compile time
  (`UnsupportedFloatWidth.refuseQuantized`); `make-quantized-matrix` and the two accessors a
  CALL-TIME signal with the same sentence (a spliced library's dead arm);
  `quantized-matrix-p` -> `(progn x nil)`. `--no-gc`: all four names refused at compile time.
  Pinned by `WasmLispCompilerTest` / `NoGcWasmCompilerTest`; ci-spec `refusedOn`.
- `--gpu`/`--blas`: silent decline. `rontolisp:jvm-export`: not a boundary type.

## What it costs (2026-09-05, GB10, `.todo/672-.../README.md`)
One thread, 4096x4096, against the shipped f32 GEMV: **Graal 1.42-1.52x** (bf16 1.31-1.41x),
**C2 0.70-0.77x** -- slower than f32 on a stock OpenJDK. Instruction-bound, not
bandwidth-bound: the Vector API has no int8 dot-product instruction, so a block costs ~30
instructions where ggml's NEON kernel spends two `SDOT`s, and C2 compiles the chain worse
than Graal. The quarter-size bytes pay where bandwidth is the limit: `--parallel` (20
threads) 2.6-4.5x f32 under Graal (105-140 Gelem/s, past the f32 arm's 41 Gelem/s memory
wall) and 1.4-2.3x under C2. The item's 2.00x premise was a one-accumulator f32 baseline
with an FMA arm; the C2 serial regression is `.todo/706`. No size gate, for `.todo/488`'s
reason. Relative error against f64: Q8_0 7.5e-3 .. 7.8e-3, bf16 1.6e-3, f32 2e-7.

## Against llama.cpp (2026-09-05, GB10)
Raw completion of `"Once upon a time"` -- four token ids `12162 5028 264 854`, no BOS, no
template, identical on both sides by construction -- at temperature 0 over ggml-org's
`Qwen3.5-0.8B` GGUFs, `llama.cpp 0eadefebd` CPU/NEON against `examples/llama2/llama2.lisp
--simd --parallel` (the build at this item's close): BF16 file token-identical over 64
generated tokens; Q8_0 file identical for 60 tokens then a different word (`llama.cpp`'s
Q8_0 output equals its own BF16 output over all 64). Raw rather than chat because the chat
template rendering is the component twice shown to differ on the Qwen family, so what is
left to differ is the arithmetic -- and at Q8_0 the arithmetic is two different kernels.
Record and ids: `.todo/672-.../README.md`.

## Tests
`eval/QuantizedMatrixTest` (surface, ggml bytes, dequantize bound, bulk transfer, defun ==
`--simd` == `--parallel` at both widths, the declines, `linalg:row`), `eval/VecSimdQ8KernelsTest`
and `codegen/jvm/JvmSimdVectorTemplateQ8Test` (kernels against the defun transcribed, bits),
`codegen/jvm/JvmQuantizedMatrixTest` (both backends, the header past 32767, the gate),
`GgufLibraryTest` (the synthetic Q8_0 tensor), ci-spec standalone `quantized-matrix`.
Bench: `eval/Q8GemvBench`, `codegen/jvm/Q8TemplateGemvBench`, `.todo/672-.../bench.sh`.
