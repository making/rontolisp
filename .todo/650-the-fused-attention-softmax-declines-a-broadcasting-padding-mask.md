# The fused attention softmax declines a broadcasting padding mask, and the fallback round-trips every head

Difficulty: Medium

Filed 2026-09-02 while closing `.todo/500`, off the chapter-2 re-profile in `.kb/gpu.md`
("The chapter-2 step re-measured"). Read that section first; it has the full copy table.

## What the profile found

At the book's chapter-2 shapes (`d_model` 512, 6 blocks, 8 heads, batch 64) a training
step now costs **292 `cuMemcpyDtoH` and 302 `cuMemcpyHtoD`**, against 4 and 104 on
2026-08-24. **288 of each are one round trip**, ~25.9 MB each way:

```
DtoH  ... gpuMaterialize <- %T-ATTENTION-SOFTMAX <- TORCH:SOFTMAX <- SCALED-DOT-PRODUCT-ATTENTION
HtoD  ... gpuSoftmaxAxis <- LINALG::%LA-SCALED-MASKED-SOFTMAX <- %T-ATTENTION-SOFTMAX
```

`torch:padding-mask` is `(batch 1 length)` -- a query axis of extent 1 so it broadcasts
over a `(batch query key)` score. `LinalgGpu.softmaxMask` runs it through
`LinalgGpu.suffixLength`, which drops LEADING extent-1 axes only and then requires an
exact suffix match, so `(batch 1 key)` against `(batch query key)` fails on the middle
axis and `%la-scaled-masked-softmax` declines. The decline itself is free, but the
compiled fallback has to materialize the 90 KB score (`64 x 19 x 19` f32) to run the
defun -- and the defun's first act is `linalg:softmax`, which the device takes and stages
straight back up.

The counts line up exactly with the model: the encoder's 6 blocks x 8 heads and the
decoder's 6 cross-attentions x 8 heads carry the padding mask alone = **96 declines a
step forward**, and their adjoint `%la-scaled-masked-softmax-grad` declines the same way
for **192 uploads** (two operands, `g` and `out`). The decoder's 48 SELF-attention heads
carry `padding + subsequent`, a `(batch length length)` mask, which IS a suffix -- they
take the fused member and copy nothing (1 staging upload a step). Chapter 3's GPT is
unaffected: its mask is `(1 T T)`, whose leading extent-1 axis is dropped before the test.

Each upload also drains the launch queue through `awaitQueued`, which is what the step's
204 `cuCtxSynchronize` are (102 on 2026-08-24).

## The shape of the fix

Two independent halves, and they are worth pricing separately:

- **Widen the mask rule.** The kernel already indexes the mask by a row-relative offset;
  a mask that is extent 1 on an axis the score is not needs one stride of 0, which is
  what `%la-bcast-strides` computes everywhere else in this library. `suffixLength`'s
  "leading extent-1 axes dropped, then an exact suffix" is stricter than the kernel has
  to be. This is the half that removes all 288 copies.
- **Do not materialize before a decline that the device is going to take anyway.** More
  general and more delicate: the fallback defun's own members are intercepted, so the
  materialize buys nothing but a round trip whenever the FIRST thing the defun does is
  another device member. Worth measuring before designing -- it may be that the mask rule
  alone closes this program.

## Acceptance

The chapter-2 book-shape step re-measured (copies a step both ways, `cuCtxSynchronize` a
step, device-busy share, wall) against the table in `.kb/gpu.md`. Bit-identity is the
usual fused-tier requirement: the widened mask must round exactly where the declined
chain rounds, pinned by `TorchGradcheck.FUSED_PROGRAM` and `ci-spec.yaml`'s
`torch-fused-compositions`, and the Metal member (`.kb/gpu.md`, "The attention scale and
mask on Metal") has to move with it or state why it does not.
