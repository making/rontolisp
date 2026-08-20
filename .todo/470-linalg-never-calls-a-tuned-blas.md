# `linalg` never calls a tuned BLAS, and one is free on every platform we measured

Difficulty: Medium.

Found while spiking Metal for `.todo/123`. It is not a GPU item and does not belong in
that file: it is a CPU acceleration path, it covers `linalg`'s DEFAULT element width, and
on Apple Silicon it beats every GPU option in todo-123 at the sizes rontolisp programs
actually run.

**DECIDED (user, 2026-08-20): a tuned BLAS is RECOMMENDED, never required. rontolisp must
run identically without one.** That settles the item's shape and removes a question this
file previously spent two sections on. There is ONE mechanism -- find a tuned CBLAS,
verify it is tuned, use it, otherwise decline to the kernel we already have -- and the
platforms differ only in what that search finds. macOS finds Accelerate with the user
doing nothing; a Linux user is TOLD, in the docs, to `apt install libopenblas0-pthread`
and gets 5-20x for it. Neither is a dependency: nothing is bundled, nothing is
downloaded, and a machine with no tuned BLAS runs the same programs to the same output,
only slower. This is exactly `--gpu`'s posture toward a GPU, one layer down.

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
  staying the oracle. Nothing new has to be invented to plug it in -- except the
  availability predicate, which is genuinely new and is the item's real difficulty
  (point 6 below).
- **It reaches the same two backends as `--gpu`** (interpreter incl. the native binary,
  and JVM) for the same reason -- FFM -- so it inherits todo-123's native-image answer.

## What has to be built, and what is still open

Points 1-3 follow from the decision above rather than being open questions; 4-8 are
genuinely undecided.

1. **The decline path is the BASELINE, not a fallback, and it has to reject a BLAS that
   is present.** "Works without one" is not free: the DGX Spark ships the netlib reference
   at `libblas.so.3`, which is 1.6x SLOWER than the kernel rontolisp already has, so
   "found a CBLAS" must not mean "use it". The identification work in point 3 is therefore
   load-bearing rather than a nicety -- without it, recommending a BLAS would make the
   machines that ignored the recommendation slower than they are today. That is the one
   way this feature can do harm, and the only one.

2. **Recommending it makes threading a documented behaviour rather than an open
   question.** OpenBLAS's 20x is 20 CORES; single-threaded it is 5.2x. rontolisp is
   single-threaded today, so binding a threaded BLAS turns `linalg:matmul` into a
   multi-core operation, which is not something `--simd` has ever done and is invisible
   from the program's text. Accelerate threads too, so this is not Linux-specific. Since
   we are now telling people to install the thing, the docs have to say what it does to
   the machine, and `OPENBLAS_NUM_THREADS` / `VECLIB_MAXIMUM_THREADS` should be
   acknowledged rather than fought.

3. **The recommendation is a docs deliverable, mirrored en/ja.** A guide section --
   `doc/{en,ja}/guides/simd-acceleration.md` is the natural home -- saying: macOS needs
   nothing; on Linux install OpenBLAS for N x faster `linalg:matmul`; here is how to check
   which library was bound; here is what it does to precision and to thread usage. Without
   that page the feature is invisible to exactly the users it is for.

4. **Is it a flag, or is it what `--simd` means on a machine that has it?** A third
   spelling next to `--simd` and `--gpu` is a cost in itself. The honest framing may be
   that `--simd` is "use the best CPU kernel available" and Accelerate is simply what that
   resolves to on macOS -- but that silently changes what an existing `--simd` build does,
   so it is a decision, not a detail.
5. **The precision contract, and it is a bigger break than `--gpu`'s.** A tuned BLAS
   blocks and reorders its reduction, so it is not bit-identical to the scalar defun --
   that much is the same trade todo-469 already made for `#f`, and `.kb/linalg-simd.md`
   has the language for it. The new part is WHOSE reduction order it is. Today the three
   `--simd` backends agree bit for bit with each other, because we wrote all three
   kernels; a vendor BLAS makes the answer depend on which library and which VERSION is
   installed on the machine, at `linalg`'s default width. `--gpu` has the same property
   (a different device gives different bits) and is opt-in for exactly that reason, so
   the precedent exists -- but it must be decided deliberately, not inherited by
   accident, and it argues for a distinct flag rather than folding this into `--simd`.
6. **The availability probe must find a TUNED BLAS, not a BLAS. Measured, this is the
   hardest part of the item.** CBLAS is one ABI -- `cblas_dgemm` has the same signature in
   Accelerate, OpenBLAS, NVPL and MKL -- so the binding itself is two `downcallHandle`s
   and is not Apple-specific at all. What differs per platform is whether a library is
   there, and macOS is where the win is GUARANTEED rather than where it is possible.

   But "there" is not the useful predicate. Run on a DGX Spark, the probe bound
   `libblas.so.3` and measured **7-8 GFLOP/s** at both widths, flat across every size:
   the netlib reference implementation. The same machine's `--simd` matmul does n=512 f64
   in 21.2 ms against the reference BLAS's 35.3, so **binding what was found would have
   been a silent 1.6x REGRESSION at `linalg`'s default width** -- far worse than
   declining. Consequences:

   - **The soname proves nothing.** Debian's `libblas.so.3` is an `update-alternatives`
     symlink pointing at OpenBLAS when one is installed and at the reference when not, so
     ordering the candidate list by name is not a safety mechanism.
   - **Identify, then verify.** `AccelerateProbe.java` now probes for the marker symbols
     tuned implementations export (`openblas_get_config`, `mkl_get_version`,
     `bli_info_get_version_str`, `nvpl_blas_get_version`, ...) and prints a verdict against
     measured throughput. A real feature needs both: the marker is cheap and deterministic,
     the measurement is what actually decides, and a startup micro-benchmark costs time
     the availability probe should not spend -- so how to combine them is an open design
     question, not a solved one.
   - **What Linux is worth, measured.** todo-123's founding rule -- the runtime
     requirement is what the OS or the driver already provides, and nothing the user
     installs -- governs what may be REQUIRED, and by it macOS qualifies and stock Linux
     does not. It says nothing about what may be MEASURED, and an earlier draft of this
     item confused the two and declined to measure at all, which left the opportunistic
     tier's size unknown for no reason. With OpenBLAS 0.3.26 installed on the Spark it is
     **20x `--simd` threaded across 20 cores, and 5.2x on a single thread** (f64, n=512:
     21.4 ms against 1.073 / 4.137). So the tier is worth building; this item is not
     macOS-shaped after all, even though macOS is the only platform where it needs no
     precondition.
7. **Which members.** GEMM is the whole win; `dot` / `axpy` / `nrm2` are memory-bound and
   probably not worth the call. `cblas_dgemm` also covers the transposed and scaled forms
   that `linalg` currently expands into separate passes.
8. **The copy.** `linalg`'s arrays are Java heap `double[]`/`float[]` and FFM cannot hand
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
