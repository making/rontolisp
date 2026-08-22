# The narrow-float-width spike, 2026-08-22

Throwaway probes kept for reproducibility, NOT project code: outside `src/`, not in the
reactor, not formatted by `spring-javaformat:apply`, and nothing builds or tests them.
They exist so the numbers in `../482-bfloat16-a-narrow-width-that-pays.md` can be
re-derived on other hardware -- above all the pair of numbers that chose the format:
**an IEEE f16 array is never faster to compute over on the JVM (0.60x at best), and a
bfloat16 array is 1.60x faster on the same matrix.** The item was written for f16 first,
because `Float.floatToFloat16` is in the JDK; the measurement is why it is not.

Every file is a single-class JDK source-launcher program. Run each with:

```bash
java --add-modules jdk.incubator.vector <File>.java
```

`Text.java` needs no incubator module.

## The machine these numbers came from

NVIDIA GB10 (Grace Blackwell), aarch64 Cortex-X925, 20 cores, Oracle GraalVM 25.0.4.
CPU features include `fphp` / `asimdhp` (half-precision FP in scalar and NEON) and
`sve2`, so this is a *favourable* host for f16, not a hostile one. The Vector API still
reports `SPECIES_PREFERRED = S_128_BIT` (4 float lanes) -- it does not use SVE here.

A different machine changes every number; what should survive is the SHAPE: the widening
decode is ALU work that scales with the core, while the bytes saved only matter once the
kernel is DRAM-bound, and on this class of core the decode wall arrives first.

## The files

| file | question it answers |
| --- | --- |
| `Round1.java` | first round: is `Float.float16ToFloat` auto-vectorized, and does a f16 GEMV beat f32? (no, and no) |
| `Dec.java` | how fast can the widening decode possibly go? five variants against an f32 copy ceiling, plus an exactness sweep over all 65536 patterns |
| `Gem.java` | GEMV with the fastest *exact* decode, single accumulator |
| `Acc.java` | the same with 4 accumulators + FMA (the todo-480 shape), so the comparison is not dependency-chain-bound -- **this is the decisive one** |
| `Par.java` | does the picture change at 20 threads, where bandwidth is shared? (no) |
| `Text.java` | can the printer round-trip every f16 bit pattern through a shortest-decimal search, and how many digits does it need? (yes; 5) |
| `F16.java` | does JEP 508's `jdk.incubator.vector.Float16` -- already in JDK 25 -- change the answer on this host? (no: 0.20 Gelem/s with an f16 accumulator, 2.9 through `floatValue()`) |
| `Worth.java` | **the one that chose the format**: bf16 against f16 against f32, and the reuse factor at which "widen once into an f32 scratch" reaches parity |

## The results, in one place

Decode throughput, 4 Mi elements (`Dec.java`):

| variant | Gelem/s | exact? |
| --- | --- | --- |
| scalar `Float.float16ToFloat` loop | 1.92 | yes (intrinsic) |
| Giesen bit-trick, vectorized | 3.53 | yes |
| magic-multiply (`* 0x1.0p112f`) | 7.90 | no -- inf/NaN wrong |
| magic-multiply + masked inf/NaN fixup | 5.5 | yes for finite; NaN payloads differ |
| bf16 `<< 16`, for reference | 11.93 | (different format) |
| f32 -> f32 copy, the ceiling for this shape | 7.51 | -- |

The scalar intrinsic is *not* auto-vectorized into `FCVTL` by this JDK: a hand-written
vector decode is 1.8x-4x faster than the loop the compiler produces.

GEMV, 4 accumulators + FMA, f16 weights against f32 weights (`Acc.java`):

| shape | f32 Gelem/s | f16 Gelem/s | f32 GB/s | f16 vs f32 |
| --- | --- | --- | --- | --- |
| 288x288 (llama2 stories15M) | 16.08 | 5.06 | 64.3 | **0.31x** |
| 1024x1024 | 16.09 | 5.27 | 64.4 | **0.33x** |
| 4096x4096 | 8.70 | 5.13 | 34.8 | **0.59x** |
| 8192x8192 | 7.66 | 5.15 | 30.6 | **0.67x** |

f16 is pinned at ~5.1 Gelem/s at every size -- it is decode-ALU-bound and never touches
the memory wall it was supposed to relieve. f32 falls from 16 to 7.7 as the matrix leaves
cache, which is why the ratio improves with size; it never crosses 1.0, and 0.67x at
8192x8192 is with 268 MB of weights, already far past any cache on this box.

At 20 threads (`Par.java`, single-accumulator rows) f32 reaches 93 GB/s and f16 still
loses at 0.72x: the decode scales with the cores exactly as the bandwidth demand does.

## JEP 508's `Float16`, measured (`F16.java`)

`jdk.incubator.vector.Float16` (JEP 508, Vector API tenth incubator) is **present in this
JDK 25** -- in `jdk.incubator.vector`, not `java.lang`, which is what an early probe here
got wrong. It does not rescue f16 on this host:

| GEMV, 4 accumulators | Gelem/s at 1024x1024 |
| --- | --- |
| f32 Vector API + FMA | 16.17 |
| hand-vectorized exact decode -> f32 accumulator (`Acc.java`) | 5.27 |
| `Float16.floatValue()` -> f32 accumulator | 2.98 |
| `Float.float16ToFloat` intrinsic -> f32 accumulator | 2.98 |
| `Float16.fma` with a **`Float16` accumulator** | 0.21 |

The JEP auto-vectorizes `Float16` arithmetic on **x64 with AVX512-FP16**; this is aarch64,
so the scalar path runs and the value class is 25x slower than the hand-written decode.
Re-run `F16.java` on an AVX512-FP16 box before concluding anything about x64. There is
still no `Float16` *vector species* anywhere through JEP 537 (JDK 27) -- that is the thing
that would matter, and it is still exploratory in Panama's `vectorIntrinsics+fp16` branch.

## bfloat16 against IEEE f16 (`Worth.java`)

Same GEMV, same 4 accumulators + FMA, same f32 activations; only the weight format
differs. Times are one GEMV; the ratio held to within 0.03x across reuse factors 1 to 64.

| shape | f32 | f16 fused | bf16 fused | f16 vs f32 | **bf16 vs f32** |
| --- | --- | --- | --- | --- | --- |
| 1024x1024 (4 MB, cache-resident) | 0.07 ms | 0.20 ms | 0.08 ms | 0.32x | 0.88x |
| 4096x4096 (67 MB, DRAM-bound) | 1.91 ms | 3.20 ms | 1.25 ms | 0.60x | **1.60x** |

bf16 decoding is `bits << 16` -- bf16 *is* the top half of an f32 -- so it is one
instruction and the halved bytes turn into the bandwidth win. f16 needs the ~6-op bit
trick and the ALU work costs more than the bandwidth it saves. On cache-resident data
there is no bandwidth to save and bf16 gives back a little (0.88x); on anything large
enough to matter it wins.

Round-trip relative error on N(0, 0.02) weights: **f16 1.00e+00, bf16 3.89e-03**. The f16
figure is not a typo -- samples below its 6e-8 subnormal floor underflowed to zero. bf16
carries f32's exponent range so it cannot; it pays in mantissa instead (8 bits vs 11,
3.9e-3 vs 4.9e-4 on the values that survive).

## When "widen once into an f32 scratch" is worth it (`Worth.java`)

The alternative to a fused kernel: decode the whole matrix into an f32 scratch, then run
the existing f32 kernel over it. Its cost is one decode pass amortized over however many
vectors the matrix is multiplied by. At 4096x4096:

| reuse | f32 | f16 widen-once + f32 kernel |
| --- | --- | --- |
| 1 | 1.91 ms | 4.95 ms (0.39x) |
| 8 | 15.99 ms | 17.38 ms (0.92x) |
| 16 | 31.89 ms | 31.91 ms (**parity**) |
| 64 | 127.52 ms | 119.51 ms (1.07x) |

A decode-step GEMV is reuse 1, which is the worst case; batched or prefill work is reuse
= batch size. This is why the fused kernel carries the item and the scratch route is only
a fallback.

## Should this be re-run on x64?

Not before it is needed, and it cannot change the decision. Two things separate:

- **What an x64 run could genuinely move**: only the f16 numbers. x64 has had
  `vcvtph2ps` (F16C, 8 lanes) since 2013, so if HotSpot auto-vectorizes a scalar
  `Float.float16ToFloat` loop there, f16's decode cost could collapse and the f16-vs-bf16
  gap could narrow. Note that openjdk/jdk#22755, the Float16 auto-vectorization work,
  covers *arithmetic* (add/sub/mul/div/sqrt/fma), not the conversions -- so this is a
  question, not a known win. **The bf16 numbers have no such uncertainty**: a left shift
  is a left shift on every ISA, and the Vector API expresses it directly.
- **What it cannot move**: the `Float16`-accumulator kernel, the one thing AVX512-FP16
  would make genuinely fast. `.kb/vec.md`'s lane-count pin already forbids its shape:
  `FSPECIES_REDUCE` is `SPECIES_128` and not `SPECIES_PREFERRED` precisely so "a compiled
  `.class` / native binary must not answer differently on an AVX-512 host". An f16
  accumulator changes the accumulator's *precision*, not merely its lane count, so it
  would break that invariant harder than the case the pin was written for -- at any
  speed, on any host.

So the choice of bf16 is host-independent by construction, and no child item's scope
depends on an x64 number. The useful place for one is item 488, which has to measure the
fused kernel on whatever host it is implemented on anyway; add x64 to its verification
rather than re-running this spike.

Practically: every probe here is a single-file source-launcher program, so an x64 answer
costs one command on any x64 box --
`java --add-modules jdk.incubator.vector Acc.java`, then `F16.java`. Note that a standard
GitHub `ubuntu-latest` runner is unlikely to have AVX512-FP16 (Sapphire Rapids or later),
so CI would answer the F16C question but probably not the `Float16` one.

Accuracy is not the problem. A f16 GEMV over N(0, 0.02) weights lands within 0.02% of the
f64 reference at every size tried, and the f16 round-trip error is bounded by 2^-11 as
expected.
