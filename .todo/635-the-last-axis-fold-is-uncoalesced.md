# The last-axis fold is uncoalesced over a long row

Difficulty: Medium

Measured 2026-09-02 while closing `.todo/629`, as a side finding rather than an item of
it. `.kb/gpu.md` "The chains left composed" records the numbers.

## What was measured

`fold_f32` with `inner == 1` -- every `sum` / `amax` / `amin` over the LAST axis -- gives
thread `i` row `i` and walks it sequentially, so thirty-two lanes of a warp read addresses
a whole row apart. That is exactly the pattern the fused tier's row kernels were built to
avoid (`gemm.cu`, "THE ROW KERNELS' LAYOUT": a thread-per-row softmax over global memory
LOST to the five-member chain it replaced until the rows went through a transposed shared
tile).

At the book's shapes, `#f`, isolated under nsys:

| shape | bytes read | time | rate |
|---|---|---|---|
| 16384 rows x 3038 (the logits) | 199 MB | 2.06 ms | **97 GB/s** |
| 16384 rows x 384 (an activation) | 25 MB | 0.098 ms | 255 GB/s |

The short-row case is fast because the block's whole working set stays in cache, not
because the access pattern is good; the long-row case is what the pattern actually costs,
against a ~300 GB/s coalesced stream (the softmax row kernel reaches it).

## The shape of the fix

`fold` at `inner == 1` dispatches to a tiled variant: `ROW_WARPS` warps, one thread per
row, the rows streamed thirty-two columns at a time through the existing `row_tile<T>` and
`tile_load` (`gemm.cu`), the accumulator staying a sequential `double` exactly as it is now
-- so the fold ORDER, which is the value, does not move and the result stays
byte-identical to `%la-fold-axis`. `inner > 1` (an axis-0 fold, whose lanes are already
contiguous) keeps today's kernel; the launch geometry differs, so `CudaGemm.fold` has to
choose between them and launch the tiled one at `ROW_WARPS * 32` threads exactly, as the
row kernels do.

The threshold is a measurement: at 384-wide rows today's kernel is already at 255 GB/s and
the tiled one has a barrier per chunk to pay, so there is a row length below which the
plain kernel wins.

## Acceptance

`GpuTest`'s fold assertions extended to a long-row shape and still asserting EQUALITY with
the sequential reference at both widths; the crossover measured and written into
`.kb/gpu.md`'s threshold table; the training step re-measured the long way (three
interleaved rounds of `(t23 - t3) / 20`) -- the remaining 16384-cell `fold_f32` bucket is
2.3 ms a step there, so the win has to come from elsewhere in the suite to be worth it,
and if it does not, the measurement is the deliverable.
