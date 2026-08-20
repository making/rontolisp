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
  staying the oracle. Nothing new has to be invented to plug it in -- except the
  availability predicate, which is genuinely new and is the item's real difficulty
  (point 3 below).
- **It reaches the same two backends as `--gpu`** (interpreter incl. the native binary,
  and JVM) for the same reason -- FFM -- so it inherits todo-123's native-image answer.

## What has to be decided before building it

0. **The opportunistic tier is a separate question from this item, and should stay
   separate.** "Bind a tuned library the user happens to have installed" is a legitimate
   thing to want -- it is what todo-123 already says about cuBLAS ("an opportunistic
   `dlopen`, never a requirement"), and cuBLAS turns out to be preinstalled on every DGX
   and ML dev box AND on the `ldconfig` path, so the marginal cost there really is zero.
   The same sentence would cover OpenBLAS, MKL and NVPL for the CPU. But that is a
   BONUS tier with no guarantees, no test machine that represents it, and results that
   vary by installed version; this item is about the tier that is guaranteed. Do not let
   the two merge: decide and ship the guaranteed one first, and if the bonus tier is ever
   built, build it once for both CPU and GPU rather than twice.
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
3. **The availability probe must find a TUNED BLAS, not a BLAS. Measured, this is the
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
   - **The Linux case is SETTLED, and the answer is "decline".** It is tempting to read
     the DGX result as "install OpenBLAS and re-measure", and that would be the wrong
     experiment. This item inherits todo-123's founding rule -- **the runtime requirement
     is what the OS or the driver already provides, and nothing the user has to install**
     -- which is the rule that made `--gpu` compatible with the no-dependencies
     constraint and the rule cuBLAS was rejected for breaking. Measuring a
     `sudo apt install libopenblas-dev` machine would measure a configuration that rule
     forbids requiring, and would re-make the mistake already rejected one file over.
     So: macOS satisfies the rule (Accelerate, always, in the OS), Linux does not (the
     only BLAS present is the reference, which is slower than what we already have), and
     that IS the finding. The title of this item is accurate rather than parochial.
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
