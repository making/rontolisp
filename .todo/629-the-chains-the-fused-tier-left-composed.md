# The chains the fused tier left composed

Difficulty: Medium

Filed 2026-09-02 while closing `.todo/499`, which fused softmax, the exact GELU,
layer-norm's normalization and the dropout mask (`.kb/gpu.md` "The fused tier": the
per-kernel profile at the book's shapes, batch 32, that this list is read off). Each item
below is a chain that still runs one memory pass per `linalg:` member, with its measured
weight; none is worth a round on its own, together they are about a tenth of the step.

- **The attention scale and mask around each softmax**: `(torch:div score (sqrt d-k))`
  and `torch:masked-fill` are 72 `scal_f32` (9.7 ms) and 72 `where_f32` (3.7 ms) launches
  a step, half forward and half their adjoints. Folding them into the fused softmax is a
  TAPE change (two nodes become one, and `masked-fill`'s adjoint is a select) with the
  same accumulated-gradient protocol `.kb/torch.md` describes.
- **`torch:log-softmax` over the logits**: the loss's one chain at `(8192 3038)`, about
  8 ms a step (`bcast_f32` 97216 x3, `map_f32`, `copy_f32`, `zip_f32`). The row kernel
  layout of `gemm.cu` takes any row length, so a `log_softmax` row kernel and its adjoint
  (`g - exp(out) * sum(g)`) are the softmax pair with one more member each.
- **Layer-norm's affine** `* weight + bias`: two broadcast passes forward, and backward a
  broadcast mul, a zip mul and the two axis-0 folds per parameter. The normalization was
  fused without it so that the parameter gradients stay the tape's own folds; fusing the
  affine means the kernel emits `g * norm` for the weight's fold.
- **`gelu_grad_f32` runs at ~90 GB/s** (1.67 ms for 150 MB at `(32 256 1536)`) where the
  forward runs at 170: two libm calls per element (`erff`, `expf`) recomputed rather than
  saved. Whether saving `t4` from the forward (one more 100 MB write) beats recomputing is
  a measurement.
- **The fused tier on Metal**: `MetalGemm` declines all seven; the row kernels' sequential
  double folds need the software binary64 the resident tier needed there.
- **The reduction adjoint's zero upload** is `.todo/500`, not this item, and after it the
  `bcast_f32` bucket at the activation shape (52 launches, 4 ms) is what remains.
