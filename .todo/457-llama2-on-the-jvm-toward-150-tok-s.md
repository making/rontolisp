# 457. llama2 on the JVM: from 87 to ~150 tok/s (`--simd`)

Difficulty: High

Measured 2026-08-19 on `examples/llama2/` (stories15M: 60 MB of f32 weights streamed
once per token, one core, 256 greedy tokens, this 64-core box):

| | tok/s | ms/token |
|---|---|---|
| rontolisp JVM `--simd` | 87 | 11.5 |
| rontolisp JVM scalar | 23 | |
| `run.c -O2` (one thread) | 65 | |
| Java Vector-API port of run.c (kishida's gist, `SPECIES_PREFERRED` 256-bit `fma`) | 187 | 5.3 |

**UPDATE 2026-08-22: the table above is the 64-core x86 box; this project's day-to-day
machine is now the GB10 DGX Spark (aarch64, 10 Cortex-X925 + 10 Cortex-A725), and every
number moves.** Re-measured there the same day, same story, medians of three interleaved
runs, nothing pinned: rontolisp JVM `--simd` **218**, `--gpu --simd` **283**, JVM scalar
66, `run.c -O2` (one thread) **147**, the gist **297 on one thread** (its `.parallel()`
removed) and **535 as published** (its `matmul` is `IntStream.range(0, d).parallel()`,
so the 187 above was a parallel program measured on one core). The two boxes must not be
compared row by row. What survives the move is the SHAPE of the gap, and it is what this
item is about: one thread against one thread we are at 4.6 ms a token against the port's
3.4, and the ~1.2 ms difference is the boxed glue, not the GEMV. The gate below is
therefore restated for the GB10: **>= 290 tok/s single-threaded** (a narrow loss to the
gist's single-thread 297 is the goal; beating it is `--blas`'s and `--gpu`'s job, and
matching the published gist at all needs the multi-core work in `.todo/478`).

187 tok/s IS the ceiling here: a cold-cache sweep over 85 MB of matrices runs at 9-12
GB/s whatever the kernel (one 128-bit chain, four 128-bit chains, one 256-bit `fma`
chain all land there), so 60 MB/token = ~5.5 ms = ~190 tok/s. **150 tok/s = 6.7
ms/token: the GEMVs at the bandwidth wall (~5.5 ms) plus at most ~1.2 ms of
everything else.** Today: JFR puts ~80% (~9 ms) in `RontoLispSimdBridge.matvecF` and
~2.3 ms in the Lisp around it. Both halves have to move.

## Where the 9 ms of GEMV goes (it should be 5.5)

`JvmSimdVectorTemplate.matvecF` / `dotF` accumulate each row in ONE `FloatVector` on
`FSPECIES_REDUCE = SPECIES_128`, pinned to four lanes so a compiled `.class` answers
the same `(vec:dot v v)` on every host and agrees bit for bit with the WASM `f32x4`
kernels (the precision contract, `.kb/linalg-simd.md`). One dependent
`vacc.add(...)` chain per row issues 16 bytes per ~4 cycles: ~1 MAC/cycle = ~12 GB/s
of demand at 3 GHz -- exactly the DRAM bandwidth, so any per-row overhead (the
reduce, the header offsets, the result store, the per-call `newVecF`) drops the
stream below it; the real run moves ~6.5 GB/s. Hot-cache microbench, 288x288: ours
25.6 us, the same loop in a plain Java class 21 us, four interleaved 128-bit chains
15 us, one 256-bit `fma` chain 17 us.

1. **K interleaved 128-bit accumulators per row, summed in a FIXED order at the end,
   on every `--simd` backend at once** -- JVM `matvecF`/`dotF`, interpreter
   `VecSimdKernels`, the WASM f32x4 `_vec_matvec`/`_vec_dot` in
   `WasmVecSimdRuntimeBuilder`, `--no-gc`. Lane order stays host-independent
   (128-bit everywhere) and the four backends still agree bit for bit, but the value
   CHANGES from today's single chain once: every pinned f32 `--simd` reduction digit
   moves together. Write the new order into the contract in `.kb/linalg-simd.md`.
   The f64 kernels (`SPECIES_PREFERRED`) are untouched.
2. Shave the per-row fixed cost: hoist `loopBound`, avoid `reduceLanes` per row by
   keeping a vector of partial sums (store after 4 rows), or at least measure what
   the plain-Java 21 us vs our 25.6 us is -- the header-offset `x` access
   (`ox + i`) defeats the bounds-check elimination the 0-based Java loop gets.
3. The attention GEMVs have `head-size` columns (48 here; 64-128 for the Llama
   family) -- below `THRESHOLD` (128), so `matvecF` runs its scalar tail loop for
   all of them: 2 x 6 heads x 6 layers x (256 x 48) ~ 0.9M scalar MACs per token.
   The threshold guards the per-CALL vector setup, which a matvec pays once per
   matrix, not per row: give the row loop its own (much lower) threshold.

## Where the 2.3 ms of glue goes

The Lisp between the GEMVs compiles to boxed `Object` arithmetic on the JVM
(`.todo/412`): per token, `rope` rotates 2 x 144 pairs x 6 layers (~17K boxed
float ops), `attention` copies 6 x 48 query elements and 6 x 48 outputs per layer
and stores 2 x 288 cache elements, the softmax runs 3 loops over `pos` elements per
head, and `vec:add` / `vec:mul` / `vec:scale` / `linalg:row` / `vec:ones` each
allocate a fresh vector (GC pressure evicts the weight stream from cache). Levers,
cheapest first:

4. **Example-side**: the `-into` kernels for the residual/RMSNorm/SwiGLU chain (no
   allocation per op), `linalg:row` -> a reused buffer, and the RoPE tables as two
   precomputed (seq-len x dim) cos/sin vectors so RoPE becomes `vec:mul`/`vec:sub`
   over even/odd halves -- but that needs a strided or de-interleaved layout, i.e. a
   `vec:` kernel that does not exist. The softmax over `pos+1` entries could be
   whole-vector (`vec:exp`/`vec:sum`/`vec:scale` + a 0/1 keep mask) on the JVM; it
   was tried with a `-1e30` additive mask and REVERTED because the WASM software
   `exp` explodes past |x| ~ 300 (`.todo/456`) -- fix that first, then a clipped
   `(vec:clip (scores - max) -80 0)` form is safe on every backend.
5. **Backend-side, the real lever**: an unboxed fast path for `(aref packed i)` /
   `(setf (aref packed i) v)` inside a loop whose index is a fixnum and whose
   value feeds float arithmetic -- the JVM backend boxes every integer and every
   double today (`.todo/412`, "no fusion"). Even a local `double` register for the
   `let*` temporaries of `rope` would take most of the 17K boxings out.

## Gate

`examples/llama2/` at >= 290 tok/s on the GB10, single-threaded (256 tokens,
stories15M, the README table re-measured), the story still byte-identical to `run.c` on all four
backends (`examples.yaml`'s `equals`), and every `--simd` backend still agreeing on
the f32 reductions (ci-spec `comparison-select-ufuncs` / vec cases, the pinned
digits updated ONCE, together).
