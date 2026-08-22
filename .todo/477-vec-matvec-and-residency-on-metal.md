# `vec:matvec` on Metal: residency first, then the GEMV

Filed 2026-08-22 when todo-475 landed (`.kb/gpu.md`, "The GEMV, and the matrix that
stays"). Difficulty: Medium. Status: open -- needs Apple hardware; nothing here can be
measured on the Linux GB10 box the CUDA half was built on, and todo-123's own rule is "do
not start a phase without the previous one's numbers", so this is a separate environment's
item by design.

## Where it stands

`--gpu` on Apple Silicon (`MetalGemm`, phase 5) takes both product shapes, the twelve
transcendentals, and the broadcast / axes-transpose pair -- and declines `vec:matvec` at
every size (`gemv` / `gemvF` return `false`, `Thresholds.matvec = Long.MAX_VALUE`).
The reason is not the kernel. A matrix-by-vector product is one pass over its matrix, so
it pays only when the matrix is ALREADY on the device, and the Metal half keeps no
resident copies: its buffer pool is SCRATCH -- fully overwritten on the way in, fully read
on the way out, no host array's device copy outlives the call -- which is what let it be
sound with no invalidation rule. todo-475's whole design ("accept only when the matrix is
resident, or was offered once before and not written since") has nothing to stand on
there.

## What it would take, in order

1. **Residency on Metal**, the `CudaResidency` counterpart: a weakly-keyed identity cache
   from a host array to an `MTLBuffer` holding its copy, consulted before every
   `newBufferWithLength:` / upload in `MetalGemm`, with the result recorded after its
   download, and freed (returned to the size-classed pool, or released) when the array
   is collected, written, evicted or released. `Gpu.written` already reaches
   `GpuDevice.written`, which is a no-op default on this backend -- implementing it is the
   whole invalidation side, because both interceptors already report every in-place
   write (the enumeration in `.kb/gpu.md`, `read-sequence` included since todo-475).
   **Measure before believing it** -- `.kb/gpu.md` says in as many words which two things
   decided whether residency paid on CUDA, and both are platform questions: the
   fresh-page cost of a device copy (`FreshPageCost.java`'s question, to be re-asked with
   `MTLBuffer` + unified memory, where `newBufferWithBytesNoCopy:` may change the answer
   entirely), and a cap small enough that the pool keeps recycling. The first CUDA build
   with strong keys and an uncapped budget was 2.3x SLOWER than no residency.
2. **The GEMV kernel in `gemm.metal`**: `gemv_f32` only (no `double` in MSL), one
   SIMD-group per row with `simd_sum`, and the accumulator question re-asked: CUDA
   accumulates in double to land on the scalar defun's bits (1024/1024 rows); MSL has
   only `float`, so the Metal GEMV can at best match the `--simd` lane kernel's
   CONTRACT (an f32 accumulation, a different order) and will differ from both the defun
   and the lanes in the last bits -- the precision note in the guide's `vec:matvec`
   bullet has to say so for Apple, and `MetalGpuTest` pins a tolerance rather than the
   row-identity `GpuTest` pins.
3. **The threshold, re-derived on the Mac**: `MetalGemm.thresholds().matvec()` against
   `matvec-baseline.lisp` under `--simd` on that machine's JVM, with a Metal column added
   to `MatvecCrossover.java`'s question (the per-command-buffer floor is ~77 us there
   against ~9 us resident on CUDA, so expect the crossover several powers of two higher
   -- and possibly nowhere below a few million elements, in which case the honest answer
   is the decline that stands today, written down with its numbers).
4. `MetalGpuTest` siblings of the four `GpuTest` GEMV cases (two-sight rule with the hit
   count, offsets, declines, the leak/steady-state run against the pool), and the
   interceptor suites already parameterize on `GpuThresholds.matvecMinElements()` --
   they skip while it is `Long.MAX_VALUE` and start asserting the day it is not.

## Acceptance

On Apple Silicon: `examples/llama2` under `--gpu --simd` on the JVM class output decoding
`stories15M` faster than `--simd` alone by a ratio the `.kb` records, the story unchanged
at temperature 0, `MetalGpuTest` green, and `GpuDeclineTest` / every CUDA assertion
unchanged on the Linux box. If step 3's measurement says the GEMV never pays on Metal,
the acceptance is that number in `.kb/gpu.md`'s Metal section and the decline kept.
