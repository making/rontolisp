# `--gpu`: a second orthogonal acceleration flag for the `linalg:` kernels

**Status:** open, unstarted. Raised 2026-07-13 while rebuilding
`examples/browser/hiragana/` (a CNN trained in Lisp): `--simd` cut that training
from ~20 min to 4m39s on the JVM, and the next order of magnitude — the one that
would let us train a BIGGER net on ALL of Kuzushiji-49 (232k images) rather than
a 36.7k subset — is a device, not more lanes.

## Why this is cheap to reach for (the seam already exists)

`--simd` is not "vectorized linalg". It is an **interception protocol** with two
properties that a GPU backend inherits for free (`.kb/linalg-simd.md`):

1. **The scalar `linalg.lisp` defun is the oracle and is never rewritten.** A
   kernel is a PARTIAL function: it returns null = declined for anything it does
   not handle (boxed array, mixed widths, shape mismatch, ...), and the call site
   then runs the Lisp defun, which supplies the exact behavior and error message.
2. **The flag is orthogonal.** A build without it is byte-identical to one that
   never knew the flag; ci-spec never passes it.

So `--gpu` should be *another* flag over the SAME call sites, not a new backend:
a user program (`examples/browser/hiragana/train.lisp`, the whole
deep-learning-from-scratch port) gets faster **without one line changing**. The
decline protocol also gives size-based dispatch for free: a matrix too small to
be worth a kernel launch simply returns null and runs on the CPU.

`--simd` and `--gpu` must compose or clearly exclude each other (decide: probably
"`--gpu` wins where it accepts, `--simd` picks up everything it declines").

## Where the time actually goes (measure before building)

At the hiragana CNN's shapes (JVM, one epoch over 2000 samples): 6.13 s scalar ->
1.45 s with `--simd` (4.2x). Inside that, the convolution is `%la-im2col` +
`linalg:matmul` + `col2im`. The matrix product is what a GPU is for; im2col is
index arithmetic (already an intercepted native kernel, todo-117 `8987590`, no
lanes to add).

**Do not start without a profile at the target scale** (batch 128-512, a net an
order of magnitude bigger than the demo's 150k params). At the CURRENT demo scale
a GPU may well be SLOWER than `--simd` — the kernels are tiny and launch/transfer
overhead dominates. The honest framing of this todo is "make a scale we cannot
train today trainable", not "make today's training faster".

## The two real design problems (neither existed for `--simd`)

1. **Device residency.** Intercepting one `matmul` call and copying host->device
   ->host per call is a loss at these sizes: the transfer is the cost. The win
   needs a linalg array that can LIVE on the device across a whole forward/backward
   pass (a device handle inside `LispFloatArray`, or a lazily-evaluated expression
   graph flushed at the first read). This is the crux of the design and the reason
   this is not a weekend port of `LinalgSimd`.
2. **Bit-identity breaks.** A GPU matrix product reorders its reductions, so
   results differ in the last bits. Today the matrix product is EXEMPT from the
   f32-reduction contract (it is bit-identical under `--simd`). `--gpu` cannot keep
   that. Precedent exists (`.kb/linalg-simd.md`'s todo-106 precision contract):
   document it as an opt-in precision contract, keep the scalar defun as the
   cross-backend oracle, and keep `--gpu` out of ci-spec.

## Routes per backend (no pure-Java GPU exists)

- **JVM / interpreter**: Panama FFM binding to a BLAS (cuBLAS on NVIDIA, Metal
  Performance Shaders / Accelerate on macOS). Native-image needs the same FFM
  config care as the Vector API did (`.todo/102`: `-H:+VectorAPISupport` vs
  `-H:+SharedArenaSupport` were mutually exclusive — expect a similar fight).
- **Browser (wasm-GC)**: WASM cannot reach WebGPU directly. The module would
  declare the compute entry points as host functions (`rontolisp:wasm-import`)
  and the page's JS would own the WebGPU dispatch. Note the hiragana demo's
  inference is 27 ms per stroke for ONE 24x24 image — a GPU would probably make
  that WORSE. Browser GPU only pays for batch inference or a much bigger net.
- **WASI**: `wasi-nn` exists but is inference-only (no training), so it does not
  serve the motivating use case.
- **`--no-gc`**: out of scope (no arrays, no linalg).

## Suggested first step

A spike, not a feature: bind ONE kernel (`linalg:matmul`) through Panama to the
platform BLAS on the JVM, keep everything else on the existing path, and measure
the hiragana trainer and a 10x-bigger variant of it. If device residency turns
out to be mandatory even for that one kernel (likely), the spike's real output is
the residency design, and only then is the flag worth building.

## References

- `.kb/linalg-simd.md` (the interception protocol, the precision contract),
  `.kb/linalg.md`, CLAUDE.md "`--simd` is the ONE orthogonal acceleration flag"
  (this todo would make that line say "the FIRST of two").
- `.todo/121` (the remaining un-intercepted `--simd` member tier: comparison
  masks + take-rows/gather/one-hot — cheaper, do it first).
- `examples/browser/hiragana/` (the workload that motivated this),
  `examples/deep-learning-from-scratch/ch07`, `ch08`.
