# `examples/llama2` decode on the device: `vec:matvec` with the weights resident

Filed 2026-08-22 from the `--gpu` follow-up round (`.kb/gpu.md`, "The second profile").
Difficulty: High. Status: open -- measured against the copy route, not built.

## The shape of the problem

`examples/llama2/llama2.lisp` decodes one token at a time, so every weight matrix in the
model multiplies a VECTOR: the forward pass is GEMV (`vec:matvec`) over 60 MB of
single-float weights per token for `stories15M`, 15 million multiply-adds, and the
README's ceiling is memory bandwidth -- 87 tok/s on the JVM today against ~190 for the
Java Vector API port that saturates it. `--gpu` does not reach it: the flag intercepts
`linalg:` members only (`.kb/gpu.md`, "What is deliberately NOT here": "nothing at all
outside `linalg:`"), `vec:matvec` is a `vec:` member, and `.kb/gpu.md` declines even the
`linalg:` gemv shapes because "a matrix-by-vector product is memory-bound, so its whole
cost is one pass over an operand the device would have to be handed anyway".

## What the measurement says

That sentence is true per call and false per TOKEN once the weights do not move:

- The GB10's pageable copy runs at ~53 GB/s (`ZeroCopyRoute.java`, R0), so even copying
  all 60 MB every token is ~1.2 ms, against ~11 ms for the CPU GEMVs of one token. A
  device GEMV per matrix plus a ~15 us round trip floor for each of the 13-per-layer
  launches is ~1.5 ms/token: already several times the CPU.
- With the weights RESIDENT -- and llama2's weights are never written after the loader's
  `read-sequence`, so an identity-keyed cache (`.todo/474`) would never invalidate them --
  a token is 60 MB of device-memory reads at the device's own bandwidth plus the
  launches: on the order of a millisecond, i.e. several hundred tok/s against 87, from a
  program that would not change.

## What it would take, in order

1. A device GEMV kernel (`gemm.cu` already has the tiled GEMM; a GEMV wants its own
   memory-bound kernel, one warp per row) and a `Gpu.matvec` over it, with the usual
   decline protocol and a threshold measured by a `*Crossover.java` probe.
2. A `--gpu` interceptor over `vec:matvec` -- a NEW seam: `VecSimd` / `JvmSimdCompiler` /
   `WasmVecSimdCompiler` intercept `vec:` today, `LinalgGpu` / `JvmLinalgGpu` intercept
   `linalg:`; this item is the first `vec:` member on the device, and `.kb/vec.md`'s
   threshold and precision rules (the pinned 128-bit accumulation order of the `--simd`
   kernel, which the device will NOT reproduce bit for bit -- so the llama2 acceptance
   text would become "agrees to a tolerance" rather than "byte-identical to run.c" with
   the flag on) have to be decided before any of it.
3. Residency for the read-only operands (`.todo/474`), without which step 1 is ~5x and
   with which it is a different order of magnitude. The two items are independent to
   build and multiplicative in effect; this one is the measurement that makes the other
   one's read-only half -- the simplest half, no invalidation ever fires -- worth
   building first.

## Acceptance

`LLAMA2_CHECKPOINT=stories15M.bin` under `--gpu --simd` on the JVM class output decoding
at a multiple of the `--simd`-only tok/s, the story unchanged at temperature 0 to within
whatever tolerance step 2 decides and documents, and `examples/examples.yaml`'s llama2
entries still byte-identical WITHOUT the flag.
