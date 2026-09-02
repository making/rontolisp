# The attention head's transpose is still a copy

Difficulty: High

Filed 2026-09-02 while closing the linear backward's transposes. Read `.kb/gpu.md`
"The transposed product" first: it has the before/after profile this is the remainder of.

## What is left

The two matmul adjoints now reach the stacked product through `linalg::%la-matmul-nd-ta`
/ `-tb`, which read the transposed operand where it lies. What that did NOT reach is the
transpose the model itself writes:

```lisp
(torch:matmul query (torch:transpose key '(0 2 1)))   ; transformer/attention.lisp
```

`torch:transpose` is an eager tape node: it calls `linalg:transpose`, which materializes a
fresh array, and `torch:matmul` then sees an ordinary operand. At the book's shapes that
is **72 `gather_f32` launches and 2.0 ms a step** at batch 64 (36 forward, one per head
per layer, and 36 for the transpose's own adjoint) -- the last of the four gather grid
shapes in the profile, and the one the member-level fix cannot see.

## Why it is not another member

`%la-matmul-nd-tb` exists because the adjoint is OUR code and can name the member. The
attention head is a user's model, written in PyTorch's own idiom, and it must stay that
way. The only honest fix is the one PyTorch has: **a transpose the tape can carry as a
VIEW** -- `torch:transpose` returning a tensor that remembers "this is `x` with two axes
exchanged" and materializes only when something actually reads its data, with
`torch:matmul` consulting the marker and passing the orientation down to
`%la-matmul-nd-ta` / `-tb` instead.

That is a change to the tensor record itself (`torch::%tensor` has five slots, and
`torch::%t-data` is a generated accessor that every operation reads and `torch:set-data`
writes), so the difficulty is not the transpose -- it is keeping every reader of `%t-data`
honest, including the printer, `torch:item`, `torch:grad` and every `linalg:` call the
other operations make.

## Acceptance

The `(64 256 64)` `gather` bucket gone from the profile too, the step re-measured the long
way (three interleaved rounds of `(t23 - t3) / 20`; a single pair is noise), and the loss
series byte-identical to the eager transpose's.
