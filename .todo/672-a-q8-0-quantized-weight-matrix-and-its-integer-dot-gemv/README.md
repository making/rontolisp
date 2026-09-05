# The Q8_0 quantized matrix: the oracle chain and the both-JIT harness, 2026-09-05

The record for `.todo/672` (closed 2026-09-05; the mechanics live in
`.kb/quantized-matrix.md`). Three things are here that the `.kb` file only summarizes: how
the item's central check -- "the number a Q8_0 model produces is the number `llama.cpp`
produces from the same file" -- was made a legitimate check before it was run and what it
found; the kernel numbers under both JITs; and the two kernel shapes that were built,
measured and rejected before the one that shipped.

## The oracle chain, in the order it was established

**1. Prompt equality by construction, no template.** Every comparison below is a RAW
completion of `"Once upon a time"` at temperature 0. The Qwen3.5 family tokenizes it
without a BOS token, and both sides were shown to feed exactly the same four ids before
any output was read:

| side | ids |
| --- | --- |
| `llama-tokenize` / `llama-completion --verbose-prompt` | `12162 5028 264 854` |
| `gguf:tokenizer-fields` -> `tokenizer:make-bpe` -> `tokenizer:encode` | `(12162 5028 264 854)` |

Raw rather than chat, and not for convenience: our chat-template rendering is the one
component that had, by the time this was written, twice been shown to differ from the
model's own `tokenizer.chat_template` on the Qwen family (Qwen3.5-0.8B here, Qwen3-0.6B
on the other box -- both identical to `llama.cpp` in raw mode and divergent in chat
mode, while LFM2.5-1.2B is identical in both; `.todo/701`). A chat-mode comparison would
measure that defect; a raw one removes it, so what is left to differ is arithmetic.
`.todo/677`'s "same character, different sentence" was the chat harness, not the kernels.
Do not "improve" this check by making it a realistic chat prompt.

**2. The baseline was established, not assumed.** Before the Q8_0 file was compared,
the BF16 file was: `examples/llama2/llama2.lisp --simd --parallel` (f32 GEMVs over the
bf16 weights widened at load) against `llama.cpp 0eadefebd` (2026-09-01, CPU/NEON build,
`-t 8`, `--temp 0 --repeat-penalty 1.0 --top-k 0 --top-p 1.0 --min-p 0 -no-cnv`), both
over `ggml-org/Qwen3.5-0.8B-GGUF`'s `Qwen3.5-0.8B-BF16.gguf` (sha256 `9a7bed40...`,
`.todo/672`'s table). **Token-identical over the 64 generated tokens compared**;
`llama.cpp`'s 64 ids are an exact prefix of our 65 (the extra one is run.c's `-n`
counting convention in `llama2.lisp`, not a divergence), and `llama.cpp -n 66` continues
with the same next word. The 64 generated ids:

```
11 303 264 1814 1332 4156 557 1801 314 31137 11 1017 557 264 3175 2993 314 18546 2512 264
328 9241 21183 3158 271 2523 1366 11 303 1004 4472 1814 11 31137 513 1801 685 314 453 33268
11 24972 24795 11 321 54683 13 1921 303 411 2184 21183 1814 11 2414 2098 6821 6716 13 561
453 33268 321 24972
```

("`, in a world where everything was made of atoms, there was a special kind of atom
called a "superatom".\n\nYou know, in our normal world, atoms are made up of protons,
neutrons, and electrons. But in this superatom world, something really interesting
happened. The protons and neut`".) This is what makes `llama.cpp` a legitimate baseline
for the Q8_0 check at all: a reader who finds only the Q8_0 comparison cannot tell a
quantized-path difference from a standing difference between us and ggml, and that
inference is available only because this run came first. Together with the other box's
LFM2.5 result, our f32 GEMV reproduces ggml's bf16 kernels to the last token on two
architectures.

Provenance: taken twice, with a `develop` jar built from `f36bfbd` (2026-09-03) and
again with the build this item closed at (`1cb95b03` + this work), and the two runs are
identical to the last token -- which is also an independent data point for the other
box's question of whether Qwen3.5's f32 decode moved across the `2275c000..1cb95b03`
boundary: not between these two builds, on this prompt.

**3. The Q8_0 file, same method, same build.** `Qwen3.5-0.8B-Q8_0.gguf` (sha256
`37ae482d...`), loaded by `gguf:read` with every weight matrix staying a quantized matrix
(0.83 GB of blocks read straight into place; `token_embd.weight` included, which
`linalg:row` reads a row of into `#f`), GEMVs on the integer-dot kernel under `--simd
--parallel`:

| | generated ids |
| --- | --- |
| `llama.cpp`, Q8_0 | identical to its own BF16 output above, all 64 |
| ours, Q8_0 | identical to the above for **60** tokens, then `54683 303 279 2184 21183` |

i.e. "...something really interesting happened. **The protons and neut**rons" against
"...happened. **The electrons in the superatom**".

**What the number says about the flip.** The measured error of the quantized GEMV against
an f64 GEMV over the same weights (the harness's `rel.err` line, N(0, 0.02) weights,
unit gaussian activations) is **7.5e-3 to 7.8e-3 relative at every shape**, against
bf16's 1.5e-3 to 1.7e-3 and f32's 1e-7 to 3e-7 -- the 7.6e-3 the item predicted from
`Quant.java`, and `QuantizedMatrixTest.theQuantizedGemvIsCloseToTheBf16Gemv...` pins it
below 1e-2 on its own fixture. Two decodes whose every GEMV differs by a perturbation of
that size part company at the first token whose top-two logit margin the perturbation
can cross; over 24 layers and 60 greedy steps that is the expected order of event, and a
model with a wrong block layout, scale position or sign would not have survived 60
tokens. So the flip is quantitatively consistent with the measured error, and with the
template excluded by construction it is in the quantized path and nowhere else.

**The asymmetry, and which kind it is.** `llama.cpp`'s Q8_0 decode equals its BF16
decode over all 64 tokens; ours equals it for 60. Both Q8_0 implementations consume the
SAME weight bytes (the file's, and `rontolisp:quantize` writes `quantize_row_q8_0_ref`'s
bytes byte for byte) and quantize the activation to the same int8 grid (absmax / 127 per
block of 32; ggml in f32 with ties to even, ours in double with ties to even -- the same
quant except within 1e-7 of a half-integer), so their errors against the exact product
are the same size, 7.6e-3, and highly correlated. What differs is the FOLD: ggml
accumulates in f32 in its own order, ours in four f32 lanes pinned to the defun. The
reading is therefore **fold order, not accuracy** -- two realizations of one perturbation,
neither closer to the BF16 answer by construction -- and the 60-token agreement between
them is what correlated errors look like. The direct measurement that would confirm it,
the top-two logit margin at step 61 in both implementations, was not taken; if that
margin is not small, the reading is wrong and the difference is somewhere in the
arithmetic after all.

Trap met on the way, filed as `.todo/700`: `java -jar` without `--add-modules
jdk.incubator.vector` prints one warning and runs the decode on the scalar defuns at
~0.01 tok/s, which reads as a hang.

## The three kernel shapes, and the two JIT cliffs between them

The shipped kernel was not the first that worked. All three keep the integer part
(`B2S` widen, short multiply, short add of the halves, `S2I` widen, int add -- four exact
lane sums per block) and differ only in what happens to those four lanes:

1. **One horizontal reduce per block** (`reduceLanes(ADD)`), then a scalar double chain
   `acc += isum * (sw * sx)`. Latency-bound: **5-6 Gelem/s on one thread at every shape**
   under both JITs, i.e. level with the fused bf16 kernel at 4096x4096 under Graal and
   0.7x of f32 under C2, while the same code under `--parallel` reached 130 Gelem/s --
   the bandwidth was there and the chain was in the way. Kept in `Q8GemvBench` as the
   `q8 reduce/block (probe)` row.
2. **Double lanes** -- the four int lanes into two `DoubleVector.SPECIES_128` accumulators
   through `convertShape(I2D, ..., part)`. **Graal 25.0.4 does not intrinsify that
   conversion: 0.02 Gelem/s**, a thousand times slower than shape 1 (1027 ms per
   4096x4096 GEMV), with no warning; C2 ran it at 4.9-5.1 Gelem/s. Recorded in
   `.kb/vec.md` beside the C2 inlining cliff, because it is a property of the JIT and the
   next lane kernel will meet it.
3. **f32 lanes** (shipped): the four int lanes through `convert(I2F, 0)` -- exact, every
   lane sum is below 2^24 -- multiplied by `(float) (sw * sx)` into one four-lane f32
   accumulator, folded per row as `(acc0 + acc2) + (acc1 + acc3)`. The shape
   `.todo/482`'s `Quant.java` measured fast under both JITs.

The scalar `vec.lisp` defun mirrors shape 3 exactly, and can, because of a fact about the
widths and not about this kernel: a double carries 53 significant bits and
**53 >= 2 * 24 + 2**, so a product or a sum of two f32 values computed in double and
rounded once to f32 is the correctly rounded f32 operation (the innocuous-double-rounding
bound). The defun computes each f32 step in double and narrows it through a one-element
single-float array (`vec::%f32`), and `QuantizedMatrixTest` / `JvmQuantizedMatrixTest`
assert bit equality of the two at five shapes, both activation widths, serial and
`--parallel`, on both backends.

## The numbers (shape 3, the shipped kernel)

**Base commit `8108a6e0` + this fold (committed as the item's second commit). NVIDIA GB10,
aarch64 Cortex-X925, NEON 128-bit, 20 cores, Oracle GraalVM 25.0.4. `RONTOLISP_THREADS`
UNSET = 20 threads. Sequential, one JVM at a time (`bench.sh`), two full passes; load
average 1.30 / 1.37 / 0.64 immediately before the first JVM and 1.50 / 1.41 / 0.68
immediately after the last (13:26:15 - 13:26:42), nothing else on the box.** f32
activations; the f32, bf16 and Q8_0 arms hold the same N(0, 0.02) gaussians. The Q8_0
checksum line printed `q8 kernel == defun: true` at every shape under both JITs.

`eval.VecSimdKernels`, one thread, ms per GEMV and the ratio to the shipped f32 GEMV, the
two passes as a range:

| shape | Graal f32 | Graal bf16 | Graal q8 | Graal q8 probe (shape 1) | C2 f32 | C2 bf16 | C2 q8 | C2 q8 probe |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.006 | 0.74x | 0.42-0.56x | 0.32x | 0.004 | 0.74x | 0.23x | 0.21x |
| 1024x1024 | 0.063 | 0.72x | 0.71-0.72x | 0.34-0.46x | 0.049-0.052 | 0.78-0.81x | 0.28-0.30x | 0.27-0.28x |
| 4096x4096 | 1.87-2.07 | 1.31-1.41x | **1.42-1.52x** | 1.31-1.46x | 1.96-2.14 | 1.81-1.98x | **0.70-0.77x** | 0.68-0.74x |
| 5632x2048 | 1.22-1.32 | 1.23-1.28x | 1.23-1.42x | 1.19-1.35x | 1.16-1.33 | 1.57-1.79x | 0.61-0.70x | 0.59-0.67x |

`codegen.jvm.JvmSimdVectorTemplate` (the copy in every `--simd` `.class`, through the real
bridge entries over headered arrays) agrees with its eval twin to within ~0.1x at every
cell: Graal 1.36-1.49x / C2 0.68-0.78x at 4096x4096.

`--parallel`, 20 threads:

| kernels, shape | Graal f32 | Graal bf16 | Graal q8 | C2 f32 | C2 bf16 | C2 q8 |
| --- | --- | --- | --- | --- | --- | --- |
| eval, 1024x1024 | 0.030-0.035 ms | 1.35-1.55x | 0.74-0.79x | 0.024-0.027 | 1.55x | 1.03-1.06x |
| eval, 4096x4096 | 0.409-0.412 | 1.23-1.33x | **2.57-3.34x** (105-137 Gelem/s) | 0.410 | 1.17-1.20x | 1.40-1.82x |
| eval, 5632x2048 | 0.347-0.380 | 2.33-2.73x | **4.20-4.49x** (136-140 Gelem/s) | 0.375-0.382 | 2.38-2.52x | 2.28-2.30x |
| template, 4096x4096 | 0.415-0.428 | 1.29-1.91x | 2.54-2.59x | 0.421-0.424 | 1.08-1.26x | 1.44-1.85x |
| template, 5632x2048 | 0.305-0.308 | 1.87-2.06x | 2.65-2.72x | 0.334-0.360 | 2.11-2.33x | 1.63-1.77x |

Relative error against an f64 GEMV, every shape, both JITs: f32 1e-7 .. 3e-7, bf16
1.5e-3 .. 1.7e-3, **Q8_0 7.5e-3 .. 7.8e-3**.

## What the numbers say

- **The item's headline does not reproduce on one thread.** `Quant.java` measured the
  integer-dot shape at 2.00x of f32 under Graal at 4096x4096; the shipped kernel is
  1.42-1.52x, barely above the fused bf16 kernel's 1.31-1.41x, and under C2 it is
  0.70-0.77x -- SLOWER than the f32 GEMV it replaces, on the JIT a stock OpenJDK runs a
  compiled `.class` under. The item's own verify rule ("the one-thread 4096x4096 ratio
  must be clearly above bf16's, or the int-dot did not vectorize") is therefore met only
  marginally under Graal and not at all under C2.
- **The cause is instruction count, not bandwidth.** 12 Gelem/s under Graal is 13 GB/s of
  Q8_0 bytes against a ~27 GB/s single-thread ceiling this box has shown; 6 Gelem/s under
  C2 is half that. The Vector API has no int8 dot-product instruction, so one 32-element
  block costs eight `convertShape`s, four short multiplies, six adds, a convert and a
  multiply-add -- ~30 instructions where ggml's NEON kernel spends two `SDOT`s -- and C2
  compiles that chain worse than Graal does. The quarter-size bytes pay only where
  bandwidth is the limit, which is exactly what the `--parallel` column shows: 105-140
  Gelem/s under Graal (2.6-4.5x f32, past the 41 Gelem/s at which the f32 arm sits on the
  memory wall) and 1.4-2.3x under C2.
- **The `Quant.java` 2.00x was against a one-accumulator f32 baseline** that no longer
  exists (`.todo/480` gave the f32 GEMV four accumulators, `.todo/488`'s README tells the
  same story for bf16), and its integer-dot arm used a single-rounding FMA. Against the
  shipped f32 kernel the same arithmetic is 1.4-1.5x. The premise is corrected here rather
  than the kernel forced to meet it; the C2 serial regression is filed as `.todo/706`.
- The cache-resident shapes lose on both JITs (0.4-0.7x at 288 and 1024), as bf16's do;
  there is no size gate. Unlike bf16's case a gate could not change an answer here (the
  kernel and the defun are one value), but the only thing to gate TO is the scalar defun,
  which is slower at every shape, so a gate would buy nothing anywhere.

```bash
./mvnw -o test-compile
.todo/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/bench.sh both
```
