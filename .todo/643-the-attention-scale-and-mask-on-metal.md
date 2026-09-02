# The attention scale and mask on Metal

Difficulty: Medium

Filed 2026-09-02 alongside todo-641, which folded the attention scale and mask into the
softmax pair on CUDA. Read `.kb/gpu.md`, "The attention scale and mask" (todo-641's
measurements and the two traps it hit) and "The fused tier on Metal" (todo-636, what this
backend's fused row kernels are and how their bits are earned).

`GpuDevice`'s four softmax entry points now take `mask` / `maskLen` / `sop` / `sf` /
`fill`: a row cell is read as `(T)(x op sf)` with `sop` the scale's `BIN_MUL` / `BIN_DIV`
(0 for none), and the positions a non-zero mask marks are replaced with `fill` before the
fold. `MetalGemm` takes the plain call -- todo-636's fused softmax and its adjoint serve
it -- and DECLINES anything with a mask or a scale, so on an Apple machine
`torch:div` and `torch:masked-fill` around each softmax stay the two eager passes they
were.

## What that costs

Nothing incorrect: the decline lands on the eager tape nodes, which are what every
backend ran before todo-641. On CUDA those two passes were `scal_f32` 7.92 ms and
`where_f32` 7.82 ms a step at the book's shapes, both of which went to zero, for a step of
0.606 -> 0.592 s (-2.3%) and an isolated forward of 0.517 -> 0.305 ms.

**Here it should be worth MORE than -2.3%, and todo-636 is why.** A Metal call is `commit`
plus `waitUntilCompleted` and nothing overlaps, so removing two calls removes two full
waits as well as two memory passes -- the same asymmetry that made the fused tier a THIRD
of the step here against a QUARTER on CUDA. That is a prediction, not a measurement, and
the point of this item is to replace it with one.

## What todo-641 learned that transfers

Both of its traps are shaped to recur here, and the CUDA session flagged them:

- **Reading the mask cell by cell cancelled the win.** The row kernels keep one thread per
  row, so at the book's shapes there are 16384 threads and a per-cell mask load is exposed
  latency, not bandwidth (the adjoint's mask pass measured 0.19 ms against `where`'s
  0.11). What fixed it was packing the mask to ONE BIT PER CELL in the same call
  (`pack_mask_*`) and having the row kernel read one word per row and exchange it by
  shuffle. Metal has SIMD-group shuffles, so the same shape is available; the exposed
  latency is the same problem either way.
- **A `__shared__` tile per template instantiation made the PLAIN path 30% slower.** That
  may be CUDA-specific, but this backend's row kernels already hold two `32 x 33`
  threadgroup tiles per SIMD group, so a second instantiation carrying its own is exactly
  the shape to be careful of. Measure the plain path before and after adding the variant,
  not only the new one.

## Measure it the long way

`wall` swings ~4% run to run on this machine, more than todo-641's whole effect on CUDA,
so the wall column cannot decide this one -- todo-636 got away with it only because a
third is far outside the noise. There is no nsys here. Either isolate the members the way
`.todo/123-gpu-acceleration/fusion-segments.py` does (which is how todo-636's per-call
table was taken, and it resolved 2 ms differences cleanly), or drive Metal System Trace.
Decide that before building anything.

## Acceptance

`MetalGpuTest` gains the scaled/masked equality against the composed device chain (the
CUDA suite's claim at `#f`), the decline in `softmaxF` / `softmaxGradF` goes, and a
measured before/after -- per member at minimum, the step if it can be resolved -- goes in
with it. A form that LOSES here stays declined and the measurement is written down, which
is a result too.
