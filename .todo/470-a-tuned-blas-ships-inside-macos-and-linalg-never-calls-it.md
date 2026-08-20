# A tuned BLAS ships inside macOS and `linalg` never calls it

Difficulty: Medium.

Found while spiking Metal for `.todo/123`. It is not a GPU item and does not belong in
that file: it is a CPU acceleration path, it covers `linalg`'s DEFAULT element width, and
on Apple Silicon it beats every GPU option in todo-123 at the sizes rontolisp programs
actually run.

## The measurement

`Accelerate.framework` exposes a plain-C CBLAS. It is part of macOS -- no download, no
toolkit, no dependency, no size cost -- and FFM reaches it in four lines:

```java
SymbolLookup ACC = SymbolLookup
    .libraryLookup("/System/Library/Frameworks/Accelerate.framework/Accelerate", Arena.global());
MethodHandle dgemm = Linker.nativeLinker().downcallHandle(ACC.find("cblas_dgemm").orElseThrow(),
    FunctionDescriptor.ofVoid(I, I, I, I, I, I, D, P, I, P, I, D, P, I));
```

Apple M4 Max, macOS 26.3.1, ms per n x n gemm, against `linalg:matmul` under `--simd` on
the JVM on the same machine (`.todo/123-gpu-acceleration/AccelerateProbe.java`, and
`matmul-baseline.lisp` for the `--simd` column):

| n | `--simd` f64 | `cblas_dgemm` f64 | `--simd` f32 | `cblas_sgemm` f32 |
| --- | --- | --- | --- | --- |
| 128 | 0.550 | **0.012** | 0.300 | **0.005** |
| 256 | 2.600 | **0.074** | 1.450 | **0.025** |
| 512 | 22.100 | **0.341** | 11.350 | **0.094** |
| 1024 | -- | 2.645 | -- | 0.743 |
| 2048 | -- | 21.504 | -- | 5.296 |

That is 800 GFLOP/s at double and 3200 GFLOP/s at single, i.e. **35-121x `--simd`**, and
it uses the matrix coprocessor that the Vector API cannot reach at all. For scale, the
same f64 number is nearly twice what todo-123 measured for cuBLAS DGEMM on an NVIDIA GB10
(420 GFLOP/s), and `cblas_sgemm` beats a hand-written Metal kernel at every size measured.

The `--simd` columns are POST-todo-469, i.e. after `5a3e8f16` gave the f32 matmul kernel
its lanes and roughly halved that column. Measure it that way or the gap is overstated by
2x; it is still 35-121x.

## Why it is worth its own item

- **It covers the default width.** Metal has no `double` at all and `--gpu` is therefore
  an f32-only feature; this is the only measured path that accelerates `#d` `linalg`,
  which is what almost every existing program and example uses.
- **No floor.** n=64 costs 4 us against the GPU's ~85 us submission floor, so the decline
  threshold sits far lower and far more of the existing example corpus is above it.
- **It is the same seam.** `.kb/linalg-simd.md`'s protocol applies unchanged: a partial
  kernel that returns the null sentinel for anything it declines, with the scalar defun
  staying the oracle. Nothing new has to be invented to plug it in.
- **It reaches the same two backends as `--gpu`** (interpreter incl. the native binary,
  and JVM) for the same reason -- FFM -- so it inherits todo-123's native-image answer.

## What has to be decided before building it

1. **Is it a flag, or is it what `--simd` means on a machine that has it?** A third
   spelling next to `--simd` and `--gpu` is a cost in itself. The honest framing may be
   that `--simd` is "use the best CPU kernel available" and Accelerate is simply what that
   resolves to on macOS -- but that silently changes what an existing `--simd` build does,
   so it is a decision, not a detail.
2. **The precision contract, and it is a bigger break than `--gpu`'s.** A tuned BLAS
   blocks and reorders its reduction, so it is not bit-identical to the scalar defun --
   that much is the same trade todo-469 already made for `#f`, and `.kb/linalg-simd.md`
   has the language for it. The new part is WHOSE reduction order it is. Today the three
   `--simd` backends agree bit for bit with each other, because we wrote all three
   kernels; a vendor BLAS makes the answer depend on which library and which VERSION is
   installed on the machine, at `linalg`'s default width. `--gpu` has the same property
   (a different device gives different bits) and is opt-in for exactly that reason, so
   the precedent exists -- but it must be decided deliberately, not inherited by
   accident, and it argues for a distinct flag rather than folding this into `--simd`.
3. **Portability, and what happens off macOS -- less Apple-specific than the title.**
   CBLAS is ONE ABI. `cblas_dgemm` has the same signature in Accelerate, OpenBLAS, NVPL
   and MKL, so the binding is two `downcallHandle`s that are not Apple-specific at all;
   what differs per platform is only whether a library is THERE. macOS always has one, in
   the OS, at a fixed path. Linux ships none with the base system but very often carries
   one anyway (OpenBLAS pulled in by almost any scientific package; NVPL on Grace; the
   distro `libblas.so.3` alternative). So the design is a candidate-list `dlopen` with a
   silent decline -- exactly `--gpu`'s availability probe, one library list instead of a
   driver -- and macOS is where the win is GUARANTEED rather than where it is possible.
   `AccelerateProbe.java` walks that list and prints which library it bound, so this is
   answerable by running it on the target rather than by assuming; on Linux the answer
   "no CBLAS found" is a real result and means there is nothing to intercept into there.

   Note what this does NOT settle: whether a machine-dependent library is acceptable at
   all. See the contract point below.
4. **Which members.** GEMM is the whole win; `dot` / `axpy` / `nrm2` are memory-bound and
   probably not worth the call. `cblas_dgemm` also covers the transposed and scaled forms
   that `linalg` currently expands into separate passes.
5. **The copy.** `linalg`'s arrays are Java heap `double[]`/`float[]` and FFM cannot hand
   a heap array to a native call, so every call copies heap -> native. It is already in
   the numbers above and it still wins by 39x, but it is the same structural problem
   todo-123 phase 3 wants to solve, and the two should not solve it twice.

## References

- `.todo/123-gpu-acceleration.md` -- where this was found; its "two OS libraries" section
  has the full comparison against MPS and a hand-written Metal kernel.
- `.todo/123-gpu-acceleration/AccelerateProbe.java` -- the probe, re-runnable.
- `.kb/linalg-simd.md` -- the interception protocol and the decline sentinel this reuses.
- todo-469 (landed `5a3e8f16`, 2026-08-20) -- the `--simd` f32 column above is the
  post-469 one. That is the most recent thing to have moved the number this item is
  measured against, and the lesson generalizes: re-run `AccelerateProbe.java` and
  `matmul-baseline.lisp` together, never one against a stale copy of the other.
