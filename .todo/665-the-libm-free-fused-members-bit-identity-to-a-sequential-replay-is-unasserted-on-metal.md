# The libm-free fused members' bit-identity to a SEQUENTIAL replay is unasserted on Metal

Difficulty: Medium

`GpuTest.theLibmFreeFusedMembersAreTheSequentialReferencesBits` makes a strictly stronger
claim than the tier's other test. `theFusedTierLandsOnTheComposedDeviceChainsBits` holds a
fused kernel to the CHAIN OF DEVICE MEMBERS it replaces -- rounding for rounding, both
sides on the device. This one holds the three members with no library function in them
(layer-norm and its adjoint, the softmax adjoint, the dropout mask) to a **sequential Java
replay of the chain**: the row folds accumulate in a double and every member boundary
narrows to the width, so the device's answer is the CPU's, not merely the device chain's.

`MetalGpuTest` has the first and not the second. `.todo/662` classified every `GpuTest`
test against this backend (the table is in `.kb/gpu.md`, "What GpuTest claims, and where
Metal answers it") and closed seventeen of eighteen gaps; this is the one it left open, on
purpose.

## Why it was left open

It is a MEASUREMENT, not a transcription, and its answer is not known.

- On this backend the row folds run IEEE binary64 IN SOFTWARE (`gemm.metal`), which is what
  makes the resident tier land on the CPU kernels' bits at all
  (`theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit`). So the claim is
  plausible: transitively, fused == device chain (pinned) and the device chain's fold ==
  `%la-fold-axis`'s double-accumulated bits (pinned at `inner == 1` by the resident-tier
  test, and now at `inner > 1` by
  `anAxisFoldOverAResidentOperandIsTheDefunsOwnSequentialFoldAtEveryInnerStride`).
- But transitivity is not the claim. The FUSED kernel does its own row reduction inside
  one launch -- a threadgroup tree, not the fold kernel's walk -- and a tree and a
  sequential sum are the same only when every partial is exact.
- The dropout mask half is **not applicable**: `theDropoutMaskStaysDeclinedHere` pins that
  the Wichmann-Hill uniform is not a member on this backend.

## What to do

Write the Metal sibling for the applicable members (layer-norm, its adjoint onto a fresh
and an accumulated gradient, the softmax adjoint) against a sequential Java replay at
`#f`. `GpuTest`'s `layerNormGradReference` and its `nr(v, single)` width-boundary helper
are the reference to port; the shape has to clear `MIN_RESIDENT_ELEMENTS` in ROWS, as
`theFusedTierLandsOnTheComposedDeviceChainsBits` explains.

**If it does not hold, the divergence is the deliverable** and the line is re-pinned as a
BOUND rather than as bit-identity, with the reason written into `.kb/gpu.md`. That is
exactly what happened to `log-softmax` on the compiled path when `.todo/655`'s sweep first
made that tier run (`.kb/gpu.md`, "Tests", finding 2): the fused kernel took its log on the
device while the chain it replaces took the row sums' log on the host, and the answer was
NIL. A tree reduction against a sequential one is the same shape of question.

Cross-reference: `.todo/662`, which raised this.
