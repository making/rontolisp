# The `#f` matmul kernel runs without lanes, so `--simd` makes single-float SLOWER than double

Difficulty: Medium. Measured 2026-08-20 while deciding whether `torch:` should default to
single-float (`.todo/123`, phase 0) -- this is that decision's prerequisite, and it is
worth landing on its own regardless of how the dtype question goes.

## The case

Under `--simd`, the matrix product is the ONE member where `#f` is slower than `#d`.
rontolisp on the JVM, warm, 20 reps, ms/call:

| n | `#d` (f64) | `#f` (f32) | |
| --- | --- | --- | --- |
| 256 | 2.55 | 5.10 | **f32 2.0x slower** |
| 512 | 20.25 | 39.85 | **f32 2.0x slower** |

Every other member behaves the way a narrower width should. Same run, 1-d, 400k elements:

| member | `#d` | `#f` | |
| --- | --- | --- | --- |
| `add` | 1.10 | 0.62 | f32 1.8x faster |
| `exp` | 2.42 | 2.10 | f32 faster |
| `sum` | 0.28 | 0.28 | equal |
| `dot` | 0.24 | 0.28 | roughly equal |

So `--simd` speeds `#d` matmul up 8x (160 -> 20 ms at n=512) and `#f` only 3.2x
(129 -> 40). A user who opts into single-float for speed gets the opposite on the single
hottest op in the library.

## Why, and which backends

`LinalgSimdKernels.matmulF` (interpreter) and `JvmSimdVectorTemplate.laMatmulF` (JVM) are
plain SCALAR loops -- no lane loop at all -- reading `float[]` operands into a `double[]`
accumulator row. That is deliberate, and both carry the reasoning:

> the lanes run across the output row (the `j` axis of `ikj`), which carries no
> summation, so the accumulator's width is free -- and the oracle's `double` is both more
> accurate and free of the `convert(F2D)` widening these kernels otherwise avoid

The result is bit-identical to the scalar defun at BOTH widths, which is what
`.kb/linalg-simd.md` promises today and why matmul is the documented exception to the
`#f` reduction contract.

**wasm-GC is NOT affected**: `WasmLinalgSimdRuntimeBuilder.buildDot`'s `ikj` lane loop
keeps the f64 accumulator AND the lanes, widening each `#f` b-row group through
`f64x2.promote_low_f32x4`. So this is a JVM + interpreter gap, and wasm already
demonstrates the algorithm.

## What was measured, and what it rules out

`.todo/123-gpu-acceleration/MatmulFProbe.java`: the same `ikj` product, f32 in / f32 out,
three ways, against the f64 kernel as the reference. Direct Java, aarch64 (NEON, so
`FloatVector` 4 lanes / `DoubleVector` 2), ms/call:

| n | today (scalar + f64 acc) | wasm's way (F2D lanes + f64 acc) | f32 lanes + f32 acc | the f64 kernel |
| --- | --- | --- | --- | --- |
| 256 | 5.06 | **930.74** | **1.37** | 2.49 |
| 512 | 39.01 | **7477.00** | **10.38** | 19.52 |

The probe's f64 column (19.52 ms) reproduces rontolisp's own 20.25 ms, so it is measuring
the right thing.

- **Porting the wasm kernel to the JVM is off the table.** `convert(F2D)` is not merely
  "the widening a JIT is least likely to intrinsify" as the current comment puts it -- it
  is **190x slower** than the scalar loop it would replace. Bit-identical and unusable.
  Confirmed on two different input sets. Do not spend time on `convertShape` variants;
  the intrinsic is simply absent here.
- **f32 lanes with an f32 accumulator is the only fast option**, and it is fast: 2.8x
  quicker than the f64 kernel and 3.7x quicker than today's `#f` path. Landing it turns
  the table at the top from "f32 2.0x slower" into roughly "f32 1.9x faster", which is
  what the width is for.

## The contract this costs, which is the whole decision

An f32 accumulator is NOT bit-identical to the oracle. Measured against the scalar
reference on zero-mean random operands: **max ~3-4% RELATIVE error** (n=256: 3.0%,
n=512: 4.3%). That number looks alarming and needs reading correctly -- it is the worst
single output cell, and the worst cell is one whose true value sits near zero after
cancellation, so a small absolute error is a large relative one. It is the ordinary
behavior of every f32 GEMM in the industry: PyTorch's CPU `sgemm` accumulates in f32, and
so does every GPU kernel, including the one `.todo/123` measured.

Three consequences, and the third is the one to think hardest about:

1. **`--simd` would no longer be bit-identical to the scalar defun at `#f` width for
   matmul.** There is precedent -- `.kb/linalg-simd.md`'s reduction contract already says
   an `#f` reduction accumulates in single precision under the flag and promotes to f64
   once -- so this makes matmul CONSISTENT with `dot`/`sum`/GEMV rather than adding a new
   kind of exception. Matmul stops being the documented exception.
2. **The scalar defun cannot follow it.** rontolisp has exactly ONE float type and it is
   f64 (`(type-of 1.0)` is `FLOAT`; `LispNames`: "Every float shares the one double"), so
   `%la-matmul`'s `acc` is physically incapable of accumulating in single precision.
   There is no version of this change in which the oracle and the kernel agree. That is
   precisely why the reduction contract was written the way it was.
3. **All three `--simd` backends must change together, or cross-backend identity
   breaks.** This is the real constraint. wasm currently accumulates matmul in f64 and
   gets bit-identity for free; if only the JVM and interpreter move to f32 accumulation,
   the ci-spec `linalg-single-float-cross-backend` case stops agreeing across backends,
   which is a worse failure than the slowness. wasm's kernel must drop the
   `promote_low_f32x4` widening and accumulate in `f32x4` in the same commit -- which
   also makes it faster and simpler there.

An alternative worth one paragraph of thought before starting: keep bit-identity and
accept that `#f` matmul is 2x slower than `#d`. That is a coherent position -- but then
single-float is not a speed option for anything matmul-shaped, `.todo/123`'s phase 0
should be dropped, and `doc/**` should say plainly that `#f` is for memory, not speed.
Decide between the two BEFORE writing any kernel.

## Mechanics

The three established touch points (`.kb/linalg-simd.md`):

- `eval/LinalgSimdKernels.matmulF` -- an `FloatVector` `ikj` loop accumulating straight
  into the `float[]` result row, i.e. the shape `matmul` already has at f64, with
  `FSPECIES` instead of `SPECIES`. `MatmulFProbe.laneAccF32` is that kernel, verbatim.
- `codegen/jvm/JvmSimdVectorTemplate.laMatmulF` -- the same, minus the header offsets
  (`oa`/`ob`/`3 + i * p`). `LinalgSimdKernels` stays a lane-for-lane mirror of the
  template; `eval` may not depend on `codegen.jvm`, so they are kept in lockstep by hand.
- `codegen/wasm/WasmLinalgSimdRuntimeBuilder.buildDot` -- drop the `f64x2.promote_low_
  f32x4` widening on the `#f` arm and accumulate in `f32x4` into a `#f` scratch row. The
  long comment above `buildDot` documents the f64 accumulator as deliberate and must be
  rewritten, not just edited around.

`matmul` is reached through `linalg:dot`'s M.M case (and `v.M` with `n = 1`), so no new
member, no `LinalgKernelCallLayout` work, no arity change, no new decline condition.

## Acceptance

- The table at the top inverts: `#f` matmul faster than `#d`, on the JVM and the
  interpreter, at n=256 and n=512.
- `linalg-single-float-cross-backend` (and every other `#f` ci-spec case) is regenerated
  ONCE and then agrees across all four backends again, with and without `--simd`.
- `.kb/linalg-simd.md`: matmul moves OUT of the "bit-identical at both widths" claim and
  INTO the `#f` reduction contract. The "See matmulF for why the single-float sibling
  accumulates in double" cross-references in all three kernels go away. Record the 190x
  `convert(F2D)` result there too -- it is the reason nobody should try the wasm approach
  on the JVM again.
- `doc/{en,ja}/guides/simd-acceleration.md`: the precision section gains matmul; if any
  prose says single-float matmul is bit-identical, it must go, in both languages, in the
  same commit.
- `examples/llm-from-scratch/` and `examples/ml/` still print byte-identical output with
  and without `--simd`. They round printed floats to a few decimals, which is what
  absorbs the reduction contract today -- verify it still absorbs this, and if some
  example prints a raw `#f` matmul result, that example needs the rounding, not the
  kernel.

## References

- `.kb/linalg-simd.md` (the reduction contract, the precision contract, the three touch
  points), `.kb/linalg.md` (width polymorphism, todo-097), `.kb/vec.md`.
- `.todo/123-gpu-acceleration.md` phase 0 -- the `torch:` single-float default this
  unblocks, and the 44x f32/f64 device gap that motivates it.
- `.todo/467` (batched matmul, outside the intercepted set entirely) -- the same call
  path, one rank up. If both land, do 467 first: its kernel is built from `dot`'s M.M
  case, which is the very kernel this todo rewrites.
- `.todo/123-gpu-acceleration/MatmulFProbe.java` -- the measurement, rerunnable.
