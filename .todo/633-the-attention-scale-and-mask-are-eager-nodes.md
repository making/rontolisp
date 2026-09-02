# The attention scale and mask are eager nodes

Difficulty: High

Filed 2026-09-02 while closing `.todo/629`, whose largest remaining member this is. Read
`.kb/gpu.md` "The chains left composed" for the measurement and `.todo/630` first: the two
items need the SAME machinery and 630 is the one that describes it.

## What it costs

`transformer/attention.lisp` writes the book's own idiom:

```lisp
(let* ((score (torch:div (torch:matmul query (torch:transpose key '(0 2 1))) (sqrt d-k)))
       (masked (if (null mask) score (torch:masked-fill score mask *neg-infinity*))))
  (torch:matmul (torch:softmax masked :axis -1) value))
```

At the book's shapes, batch 64, that is **72 `scal_f32` launches (7.8 ms a step, after the
power-of-two divide rewrite) and 72 `where_f32` (7.8 ms)** -- half forward, half their
adjoints, each a full pass over a 16.8 MB score slab that the fused softmax is about to
read again anyway. Folding both into `softmax_*` and `softmax_grad_*` would cost the
kernel nothing: the scale is a register multiply and the mask is a `(1 256 256)` operand
that stays in cache.

## Why the tape cannot express it today

`torch:div` and `torch:masked-fill` are EAGER: by the time `torch:softmax` is handed the
masked score, both passes have already been paid, and the two intermediate arrays exist.
A peephole in `torch:softmax` that looked at its argument's node would find the work
already done. The fusion needs the producers to DEFER -- a tensor whose data is a view
("this is `x` divided by `s`", "this is `x` with `m` filled") that materializes only when
something reads it, with `torch:softmax` consulting the marker and passing the scale and
the mask pointer down to one fused member instead.

That is `torch:transpose`-as-a-view by another name (`.todo/630`): the same change to the
tensor record, the same obligation to keep every reader of `torch::%t-data` honest.
**Build 630 first**; this item is then two more markers and one wider kernel signature,
and the adjoint side is a fused `softmax_grad` that ends in a select and a scale.

## Acceptance

The `scal_f32` and `where_f32` buckets at the `(64 256 256)` grid gone from the step
profile, the step re-measured the long way (three interleaved rounds of `(t23 - t3) / 20`;
a single pair is noise), and the loss series byte-identical to the eager chain's.
