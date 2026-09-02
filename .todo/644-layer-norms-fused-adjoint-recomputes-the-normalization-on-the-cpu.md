# Layer-norm's fused adjoint recomputes the normalization on the CPU

Difficulty: Low

Filed 2026-09-02 while closing `.todo/634`, which measured this and accepted it as the
price of the fold. `.kb/linalg.md` ("the fused compositions") and `.kb/gpu.md`
("Layer-norm's affine") carry the numbers.

## The measurement

`linalg::%la-layer-norm-affine-grad` is the chain the tape used to spell:

```lisp
(list (linalg::%la-layer-norm-grad (linalg:mul g w) x eps old)
      (linalg:mul g (linalg::%la-layer-norm x eps)))
```

The second line is the zip the `torch:mul` adjoint made -- but `norm` used to be the
forward node's stored output and is not stored any more, so the defun calls
`%la-layer-norm` again: the mean fold, the deviation, the squared deviations, their fold,
eps, the square root and the division, seven passes over the activation for one array the
adjoint's FIRST line already has the ingredients of. Measured `--simd`, `#f`,
`(2048 384)`: the forward is unchanged (7.4 -> 6.8 ms, the same three members) and the
backward is **15.6 -> 20.0 ms, +28%** (2026-09-02). On a device it costs nothing -- one
kernel answers both arrays from the row statistics it recomputes anyway -- so this is a
CPU-path item only: the plain interpreter, `--simd`, and both WASM backends.

## The shape of the fix

`%la-layer-norm-grad` already computes `mu`, `dev` and `sd`. A sibling that answers
`(dx norm)` -- `norm` being the `(linalg:div dev sd)` it has in hand, the same member
boundary and therefore the same bits -- turns the affine adjoint into

```lisp
(let ((r (linalg::%la-layer-norm-grad<...> (linalg:mul g w) x eps old)))
  (list (car r) (linalg:mul g (car (cdr r)))))
```

which trades seven passes for one. The two-array answer is the shape `.todo/634` already
established (`.kb/linalg.md`, the two-output call shape); the new member needs NO device
kernel, since `--gpu` intercepts `%la-layer-norm-affine-grad` itself and only reaches this
one on a decline.

## Acceptance

The `--simd` backward at `(2048 384)` back to about the 15.6 ms it was, every existing pin
still equal -- `GpuTest`'s two fused-tier tests, `LinalgGpuTest`,
`LinalgGpuDeclineTest`, `TorchGradcheck.FUSED_PROGRAM` on the three test backends and
`ci-spec.yaml`'s `torch-fused-compositions` on all four -- and the new numbers written into
`.kb/linalg.md` beside the ones they replace.
