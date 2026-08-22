# 482. `short-float`: an f16 packed array, as a storage width

Difficulty: High (the umbrella item; the children are sized individually)

Spiked 2026-08-22. Probes and their full numbers:
`.todo/482-short-float-a-storage-only-narrow-width/` (`README.md` there is the measurement record).

Java has had `Float.floatToFloat16` / `Float.float16ToFloat` since JDK 20, and
`LispFloatArray` is already a sealed umbrella over two widths (`LispDoubleFloatArray` /
`double[]`, `LispSingleFloatArray` / `float[]`). A third permit backed by a `short[]` is
the obvious move: **half the memory of `#f`, a quarter of `#d`**, which is what decides
whether an LLM's weights fit at all.

Scope: **interpreter and JVM only.** The wasm backends, `--no-gc` and the component path
do not get the width and must refuse it loudly (item 486).

## What the spike settled, and why it changes the design

The tempting story -- "f16 halves the bytes, GEMV is bandwidth-bound, therefore f16 is
faster" -- is **false on the JVM**, on a host with hardware half-precision (`fphp` /
`asimdhp`) and with the fastest exact decode that can be written against the Vector API.
With 4 accumulators and FMA, f16 GEMV runs at a flat ~5.1 Gelem/s at every size while f32
runs 7.7-16.1: **0.31x hot, 0.67x at 268 MB of weights**, never above 1.0. At 20 threads
it is 0.72x. The kernel is decode-ALU-bound and never reaches the memory wall the narrow
width was meant to relieve.

The cause is that the Vector API in JDK 25 has no half-precision element type, so a
widening decode is ~6 integer/FP ops per lane instead of the single `FCVTL` the hardware
offers, and `Float.float16ToFloat` in a loop is not auto-vectorized either (1.92 Gelem/s
scalar vs 3.53 hand-vectorized exact, vs a 7.51 f32-copy ceiling).

It gets worse, not better, as the rest of the tree improves: `.todo/480` proposes
multi-accumulator rows precisely to lift f32 GEMV toward the bandwidth ceiling, and every
Gelem/s that buys f32 widens this gap.

**Therefore `short-float` is specified as a storage width, not a compute width:**

1. The type exists to hold data -- model weights, KV caches, datasets, files -- at half
   the bytes of `single-float`.
2. **No f16 SIMD kernel is to be written.** Where a kernel meets a `short-float` operand
   it either widens once into an f32 scratch and runs the existing f32 kernel (pays one
   pass, wins whenever the array is read more than once per widening) or declines. A
   per-element decode fused into an inner loop is the shape the spike measured and
   rejected; do not reintroduce it as an optimization.
3. Consequently the width is invisible to arithmetic, exactly as `single-float` already
   is: reads widen to a scalar `double`, writes narrow. There is no `short-float` scalar.

This is worth writing into `.kb/vec.md` (or a new `.kb/short-float.md`) when item 484
lands, with the table from the spike README, so the next reader does not re-derive it.

## Where f16 would pay, and is out of scope here

- **`--gpu`.** On the device f16 is a genuine win (bandwidth, and tensor cores for
  matmul), and the host->device copy halves too. `.kb/gpu.md` is the place; item 486 only
  requires that `--gpu` *decline* a `short-float` operand rather than misread it.
- **`jdk.incubator.vector.Float16`, on x64.** JEP 508 (Vector API, tenth incubator) ships
  a `Float16` value class, and it is **already present in the JDK 25 this repo builds on**
  -- in `jdk.incubator.vector`, not `java.lang`. It was measured, and it does not help
  here (`F16.java`): a `Float16`-accumulator GEMV runs **0.20 Gelem/s**, 45x slower than
  f32 and 25x slower than the hand-vectorized decode, and going through
  `Float16.floatValue()` into an f32 accumulator gives 2.9 Gelem/s -- still below the 5.1
  of the hand-written decode. The JEP's auto-vectorization of `Float16` arithmetic is
  stated for **x64 CPUs with AVX512-FP16**, and this host is aarch64, so the scalar path
  is what runs. On such an x64 box the table could look different; re-run `F16.java`
  there before assuming either way.
  There is still **no `Float16` vector species** (no `Float16Vector`, no
  `VectorSpecies<Float16>`) through JEP 529 / JEP 537 (JDK 27) -- vectors of `Float16`
  remain exploratory work in Panama's `vectorIntrinsics+fp16` branch. That, not the
  scalar class, is what would replace the 6-op decode with one `FCVTL` and could flip the
  result; re-run `Acc.java` and `F16.java` on the first JDK that ships it.
- **bf16**, whose decode is a single `<< 16` and measured 11.93 Gelem/s -- 2.3x the f16
  decode and above the f32 copy ceiling. If a *fast* narrow compute width is ever wanted,
  bf16 is the format to want, not IEEE binary16. It is not what `Float.floatToFloat16`
  gives, so it is a separate item and not proposed here.

## Children

| item | what it does | difficulty |
| --- | --- | --- |
| `483` | make the two-width assumption exhaustive -- prerequisite, lands first, no new type | Medium |
| `484` | `LispShortFloatArray`, `#h(...)`, `:element-type 'short-float` on the interpreter | Medium |
| `485` | the same width on the JVM backend; the embedded header does not fit in a `short` | High |
| `486` | the backends that do not carry it must refuse it, and `--gpu`/BLAS must decline it | Low |
| `487` | conversion and bulk width change: the `-bits` pair, `coerce`, `read-sequence` | Medium |
| `488` | the payoff: `examples/llama2` at half the weight memory | Medium |

Order: 483 first (it is a pure refactor and makes every later site a compile error rather
than a silent misroute), then 484, then 485 and 486 in either order, then 487, then 488.

## The name: one vocabulary, and the single documented exception

The width has two plausible names -- CL's `short-float` and everyone else's *half* -- and
mixing them arbitrarily is how a reader ends up unsure whether `#h` and `short-float` are
the same thing. The rule, which is the rule the existing two widths already follow:

- **Every Lisp-visible name and every Java identifier uses `short-float` / `ShortFloat`.**
  So: `:element-type 'short-float`, `array-element-type`, `type-of`, `short-float-bits` /
  `bits-short-float`, and on the Java side `LispShortFloatArray`, `LispNames.SHORT_FLOAT`,
  `FloatText.shortText`. This matches `LispSingleFloatArray` <- `single-float` and
  `LispDoubleFloatArray` <- `double-float` exactly, and it is the ANSI CL spelling for the
  narrow float subtype -- `PackageRegistry.CL_SYMBOLS`, `ArgumentShapes`,
  `DeclaredArrayTypes` and `LispMacroExpander`'s type lattice already carry `SHORT-FLOAT`
  as a name that folds to `FLOAT`, so the type tables mostly already agree with
  themselves. `half-float` would be a second spelling for a thing CL already names.
- **The one exception is the reader/printer prefix, `#h(`** -- and it is an exception
  only in appearance, because that family never spelled the CL type name either: `#d` is
  `double-float` and `#f` is `single-float`, i.e. the C/numpy width word, not the type.
  `#h` is the same convention one width down (C `_Float16`, numpy `half`, GPU `half`).
  It is also forced: `#s` is already the structure literal, case-insensitively
  (`LispLexer` accepts both `#S` and `#s`), so the CL-derived letter is unavailable.

Prose may say "f16" for the storage format -- that is what the *bits* are called, in this
item and in the spike README -- but it is never a name in the code or in the language.
