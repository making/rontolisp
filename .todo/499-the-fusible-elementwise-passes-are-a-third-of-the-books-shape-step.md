# The fusible elementwise passes are a third of the book's-shape step

Difficulty: High

Filed 2026-08-23 while closing `.todo/496`. Read `.kb/gpu.md` "The launch pipeline, and what a
step is actually bound by" first -- its PyTorch decomposition is the target. The
round-by-round tables it was filed off are in the git history of that file.

## Where it stands

After todo-496 the book's-shape training step (0.695 s) is DEVICE-bound: ~0.72 s of
kernel time, of which ~250 ms is the IEEE-f32 product (that half of the gap is the width
question, `.todo/482`-`490` -- PyTorch's "eager fp32" runs TF32 tensor cores) and ~475 ms
is elementwise/strided memory passes. PyTorch eager spends 133 ms on the same graph's
elementwise work because its ops are FUSED single kernels where ours are one full memory
pass per `linalg:` member:

- dropout: `fused_dropout` = one pass; ours = rng_fill + compare + mul + scale = four
  passes (at the score shape, 4 x 100 MB).
- softmax: warp softmax fwd/bwd = one pass each; ours = amax/sub/exp/sum/div (~5 passes,
  ~20 ms/step forward at these shapes) and the tape's mul/sum/sub/mul backward.
- layer-norm: `layer_norm_kernel` / `layer_norm_grad_input` = one pass each; ours =
  the torch.lisp composition (~8 members forward, more backward), ~15 ms/step forward.
- GELU: one kernel; ours = the erf composition.
- transposes: views; ours = `gather`/`copy` passes (121 + 180 a step at the activation
  shape).

Total fusible: roughly 100-150 ms/step of the 475. The launch half of fusion's old
promise is GONE (todo-496 opened the pipeline; launches are 2.5 us and overlapped) --
what fusion buys now is only the removed memory passes, so measure per member before
building.

## The catch

Forward fusion of `linalg:softmax` (and a would-be `linalg:layer-norm`) can stay inside
the flag's precision contract (the fused kernel can replay the composed device chain's
arithmetic element for element). BACKWARD fusion cannot be a library-only change: the
adjoints live in `torch.lisp` as compositions, so a fused backward is a hand-written
adjoint -- a tape-semantics change whose float ordering moves CPU outputs too, which
re-pins ci-spec/doc expectations across all four backends. Dropout fusion additionally
pins the rng: linalg:seed promises the SAME sequence on every backend, so a fused
dropout must consume Wichmann-Hill exactly as `%la-rng-fill` does.

## Acceptance

Per-member measurements at the book's shapes deciding which fusions pay, the paying ones
built without breaking bit-identity where the contract promises it, and the step's
elementwise share re-measured against PyTorch's 133 ms.
