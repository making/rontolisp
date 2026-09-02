# Metal's `where` refuses the mask width every model builds

Difficulty: Medium

Found while measuring todo-643. `MetalGemm.whereF` opens with

```java
if (m instanceof double[]) {
    return false;
}
```

-- "a `double[]` mask is a hard decline like every double operand here" -- and the mask a
model actually hands `torch:masked-fill` is a `double[]`, because
`torch:subsequent-mask` is `(linalg:triu (linalg:ones ...))` and `linalg:ones` builds
double unless asked otherwise. So at the book's shapes `linalg:where` under a causal mask
ran on the CPU over a MATERIALIZED score: 16.8 MB down, a scalar select, 16.8 MB back up.
Measured at the `(64 256 256)` score, per call: **7.9 ms with the double mask against 1.2
ms with a single one** (`.todo/123-gpu-acceleration/mtl-attention-softmax.lisp`,
`.kb/gpu.md`, "The attention scale and mask on Metal").

## Why the decline is wider than it needs to be

The rule it inherits -- no double operand on a backend with no `double` -- is right for an
operand that enters ARITHMETIC. A `where` mask does not. `linalg:where`'s test is
`(/= m 0)`: any bit but the sign set, which is an INTEGER test on the raw word, and
todo-643's `pack_mask` already does exactly that for either width, reading a `double[]` as
two `uint`s per cell (low word first) and never widening anything. The value operands and
the result are the ones that must be single, and they already are.

So `where_f32` could take a double mask by binding that buffer as `device const uint*` and
testing `(lo | (hi & 0x7fffffff)) != 0`, with the host staging the mask at its own width --
`MetalGemm.Call.stageMask` and `lookupBytes` are todo-643's and do that part already.

## What to measure before and after

- The per-call row above is the headline, but the honest question is the STEP: with
  todo-643 landed, the causal `masked-fill` around each softmax is gone from the tape
  already, so what is left for this is `torch:masked-fill` OUTSIDE a fused softmax (the
  top-k threshold in `examples/llm-from-scratch/gpt/model.lisp`, a padding mask combined
  with `linalg:add`, `torch:where` in user code) and every other `linalg:where` whose mask
  a program built at the default width. That may be small; **if it is, the measurement is
  the deliverable and the decline stays** with the numbers written into `.kb/gpu.md`.
- Whether the same reasoning reaches any other operand this backend refuses on width
  alone. It should reach none: `where`'s mask is the only operand in the library that is
  read as a predicate rather than a number.

## Acceptance

Either `whereF` takes a double mask with `MetalGpuTest` pinning its bits against the CPU
select at both mask widths, or the decline stays and `.kb/gpu.md` carries the measurement
that says why.
