# The `--gpu` feasibility spike (CUDA 2026-08-20, Metal 2026-08-20; phase 5's own probes 2026-08-21)

> The paths in this record predate 2026-09-05, when `examples/llama2` was renamed to
> `examples/llm` and `llama2.lisp` to `llm.lisp` (`.todo/682`). They are left as they
> were: this is a dated measurement, and rewriting its paths would make it claim it was
> taken against a tree that did not exist when it ran.


Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so that the numbers in `../../../.kb/gpu.md` can be re-derived on
other hardware -- especially the decisions those numbers drove (per-call intercept before
residency; PTX-in-the-jar instead of a runtime toolkit; and, on Apple, that the naive
kernel is the wrong thing to ship at all).

The item these were written for, `../../123-gpu-acceleration.md`, is closed and deleted --
this directory outlived it deliberately, because `.kb/gpu.md` cites these files by path.
Where the text below still says "that file", it means the deleted item, which
`../../.history.md`'s row reads back: `git show <commit>~:.todo/artefacts/123-gpu-acceleration.md`.
`.kb/gpu.md` is the live authority and was measured second wherever the two disagree.

Every file is a single-class JDK source-launcher program: no build step, no dependency,
Java 22+ for the FFM API. `--enable-native-access=ALL-UNNAMED` silences the restricted-
method warning. `Cu*`/`*Spike`/`Ptx*`/`Ni*`/`Tf32*` are the CUDA side and run only on a
machine with an NVIDIA driver; `Mtl*` and `AccelerateProbe` are the Apple side and run
only on macOS. Nothing here runs on both.

## The two machines these numbers came from

- **CUDA:** NVIDIA GB10 (Grace Blackwell, `sm_121`, 48 SMs, unified addressing + managed
  memory), aarch64, driver 580.173.02 / CUDA 13.0, Oracle GraalVM 25.0.4. `nvidia-smi`
  and `/usr/local/cuda` present.
- **Metal:** Apple M4 Max (40 GPU cores, Metal 4, unified memory, 110 GB recommended
  working set), macOS 26.3.1, Oracle GraalVM 25.0.3. **No Xcode**: `xcrun metal` is
  absent, which is itself a result -- see `MtlCompileCost`.

A different device changes every number below; what should survive is the SHAPE of each
result. One Apple-specific caveat before reading any of it: Apple GPUs ramp their clocks,
so a short warm-up under-reports, and a kernel that runs for well under a millisecond can
flap by 2-3x between runs. Every `Mtl*` probe reports a min over many reps after a
warm-up, and the sub-millisecond rows still move by ~20% run to run.

## The files

| file | question it answers |
| --- | --- |
| `Cu.java` | the binding itself: `libcuda.so.1` + `libnvrtc.so.13` through pure FFM. Every other file uses it. Nothing else here is more than a driver for it. |
| `MatmulSpike.java` | does a GPU matmul beat the CPU, and where is the crossover? Also holds `SRC`, the CUDA C the other probes compile. |
| `DumpPtx.java` | writes `gemm_<arch>.ptx` -- the build-time artifact the real feature would check in as a resource. |
| `PtxSpike.java` | the three questions the design hangs on: (1) does checked-in PTX load with ONLY the driver, (2) does unified memory remove the copy, (3) how far is the naive kernel from cuBLAS. |
| `ResidencySpike.java` | the 2026-07-13 draft's crux: must arrays LIVE on the device, or does a per-call intercept pay? Plus the batched rank-3 product (todo-467's member). |
| `TinySpike.java` | the fixed per-call floor, which is what the size threshold is built on. |
| `Tf32Check.java` | rules out the obvious objection to the 44x f32/f64 gap: is cuBLAS's f32 row secretly TF32? |
| `CublasEndToEnd.java` | and is cuBLAS worth the toolkit at all? Both kernels, both phases (with copies / resident), both widths. |
| `NiProbe.java` | does a CUDA downcall survive GraalVM native-image next to `-H:+VectorAPISupport`? |
| `MatmulFProbe.java` | no GPU at all: why was `#f` matmul SLOWER than `#d` under `--simd`, and what would fix it? Drove todo-469 (landed `5a3e8f16`, 2026-08-20 -- the kernel now takes f32 lanes); kept because `.kb/linalg-simd.md` cites it and it answers differently per architecture. |
| `matmul-baseline.lisp` | the CPU side of the comparison -- `linalg:matmul` under `--simd`, warm, 20 reps. Not a GPU program. |
| `CuLib.java` | the DRIVER-ONLY binding the three `am.ik.gpu` probes below share: no NVRTC, no toolkit, the checked-in `src/main/resources/am/ik/gpu/gemm.ptx` loaded straight from the repository. `Cu.java` one design generation on -- deliberately the shape the shipped library has, so its numbers are the library's numbers. |
| `AllocatorCost.java` | what does PER-CALL device memory cost, and what does that do to the floor? The question the spike probes above never asked, because every one of them allocated once and then looped. Also: what a FAILED pooled allocation costs the device, and the two calls needed to give it back. |
| `CopyRoute.java` | does `Linker.Option.critical` remove the host copy here as it did for `--blas`, and what bounds the window in which the thread cannot reach a safepoint? Includes the GPU-only half of that question -- a device-to-host copy on the null stream also waits for the KERNEL. |
| `WorthCrossover.java` | the GPU half of `am.ik.gpu`'s `worth()` threshold, in the shipped route. The CPU half is `matmul-baseline-warm.lisp`. |
| `matmul-baseline-warm.lisp` | the same CPU baseline as `matmul-baseline.lisp`, JIT-warm (200 warm-ups, 4000 reps) and down to n=16. The original's 3 warm-ups over-report n<=64 by ~10x, which is exactly the range the size threshold is decided in. Not a GPU program. |
| `width-baseline.lisp` | `#f` against `#d` across matmul / add / dot / exp / sum, which is how the matmul anomaly surfaced. Not a GPU program. |
| `ElementwiseCrossover.java` | phase 4b's question: does an ELEMENT-WISE member pay for a round trip, and WHICH ones? Round trip against `--simd` at seven sizes and both widths, for the members the library takes AND the ones it refuses -- a decline is a measurement too. Its third table is phase 3's input: the same kernel over buffers already on the device, which is what residency would remove. |
| `elementwise-probe.cu` | the kernels that probe uses -- NOT the shipped ones. It carries `sqrt` / `abs` / `negative` and a binary `add`/`sub`/`mul`/`div` zip, which `am.ik.gpu` deliberately does not, so the refusals stay re-derivable. `nvcc -arch=compute_75 -ptx elementwise-probe.cu -o /tmp/ew.ptx`, then pass that path to the probe. |
| `elementwise-baseline.lisp` | the CPU side of that crossover: the same members under `--simd` on the JVM class output, JIT-warm, at the same sizes. Every call is a LITERAL call form -- `(funcall #'linalg:exp a)` would measure the scalar defun, since the compiled backends intercept at the call site. Not a GPU program. |
| `gemm-tile-probe.cu` | (2026-08-22) is the 16x16 one-output-per-thread GEMM the library shipped leaving speed on the table, and can a faster kernel keep its bits? Three register-tiled candidates (2x2, 4x4, 8x8 outputs per thread over 32/64/128-square block tiles) against the shipped kernel at the training step's own stacked shapes, the guide's n x n table at both widths, the book's batch-64 shapes and ragged edges, each checked cell-for-cell against the old kernel's bits. Drove the `gemm_batched_f32_t4` / `_t8` entry points and `CudaGemm.tileF32`'s SM-count rule (`.kb/gpu.md`, "The register-tiled f32 GEMM"). `nvcc -O3 -arch=native -o gemm-tile-probe gemm-tile-probe.cu && ./gemm-tile-probe` (add any argument for the threshold-zone sweep). Unlike the rest of this directory it is CUDA C, not Java. |
| `StridedCrossover.java` | phase 3's question: the members a training step still spends its time on are a BROADCAST binary op, an AXIS fold and an axes TRANSPOSE, and their CPU twins are SCALAR ODOMETER walks rather than the lane loops phase 4b measured `add` and `sqrt` at. Measures all three through the shipped route, at the shapes `train-gpt-soseki.lisp` produces at the notebook's own and at a size sweep for the two thresholds. Its CPU half is `shaped-baseline.lisp`. Needs the checked-in PTX to carry the six strided entry points, so run it against a tree at or after phase 3. |
| `ZeroCopyRoute.java` | the 2026-08-22 question: on a unified-memory machine (the GB10 answers `PAGEABLE_MEMORY_ACCESS` 1), can the copies go away? Five routes for one f32 map -- today's critical copies, pinned staging + DMA, zero-copy over pinned buffers with Java copies, the kernel over host memory with no copies at all, and the kernel over device memory -- plus the device attributes. The answer (`.kb/gpu.md`, "The CUDA backend") is that the only safe zero-copy loses to the driver's pageable copy, so the route stayed. |
| `RngCrossover.java` | where the seeded generator's fill (`linalg::%la-rng-fill`, the device member added 2026-08-22) starts to pay: the shipped `Gpu.rngFill` against the sequential walk, every rule and both widths, asserting bit identity on every row. Sets `Gpu.RNG_POOLED_MIN_ELEMENTS`. Needs the library's classes: run with `-cp target/classes` from the repository root. |
| `ResidencyCost.java` | todo-474's first question (2026-08-22): does the mere EXISTENCE of live stream-ordered allocations -- what a cache of resident device copies is -- make the next alloc + copy + free cycle slower? One 1 MB cycle with nothing else alive, with 64 / 512 / 2048 live blocks, after freeing them, and the growing never-freed pattern; with the pool's release threshold at the driver's default and at the maximum (argument `keep`). Answer: no -- 24 us a DtoH throughout -- which is what pointed at the host side. |
| `MatvecCrossover.java` + `matvec-probe.cu` + `matvec-baseline.lisp` | todo-475's question: where does a device GEMV (`vec:matvec`) beat the `--simd` lane kernel, and does it beat it at all when the matrix is NOT resident? Three device columns per shape -- cold (W up every call), resident (W uploaded once), kernel only -- over llama2 stories15M's own shapes and a sweep of squares, plus the accumulator question (a float accumulator against a double one, speed AND distance from the scalar defun's bits) and a float4-load variant. `matvec-baseline.lisp` under `--simd` on the JVM class output is the CPU column. Answer (`.kb/gpu.md`, "The GEMV, and the matrix that stays"): cold loses until ~2^19, resident wins from 2^17 and floors at ~9 us, and the double accumulator is bit-identical to the defun on 1024/1024 rows where the float one is on 268. Needs `nvcc` once, for its own PTX (command in the `.cu` header). |
| `FreshPageCost.java` | todo-474's second question and the one that decided the route: what does a device copy cost when the HOST array is FRESH? Every earlier probe reused one host array. A reused array against a fresh one per iteration, with and without eden churn; then which first access warms a fresh array for the device (a DtoH, an HtoD, a CPU store per page, a CPU copy into it). Answer: ~9 us per 4 KB page on the GPU's first touch, 2.3 ms for 1 MB against 25 us, and a CPU copy INTO the array beforehand is what warms it -- why `am.ik.gpu` stages every download through a pinned bounce buffer. |
| `shaped-baseline.lisp` | the CPU side of that crossover: the same members under `--simd` on the JVM class output, at the same shapes and the same sweep, plus the whole `softmax` / `log-softmax` / `layer-norm` chains for the end-to-end comparison. Run it twice, once per flag, and pair the columns. Not a GPU program. |
| `pytorch-gpt-soseki.py` | (moved 2026-11-18 to the `llm-from-scratch` project's `bench/`, which holds the library it loads) (2026-08-23, todo-492's closing measurement) the chapter-3 GPT at the book's shapes written against PyTorch -- the book's per-head attention, AdamW, clipping, dropout 0.1 -- for the step-time comparison in the `llm-from-scratch` project's README: `python pytorch-gpt-soseki.py fp32|bf16|compile [steps]` with `soseki.txt` beside it, run in NVIDIA's container (`docker run --gpus all --ulimit memlock=-1 --ulimit stack=67108864 --rm -v $PWD:/work -w /work nvcr.io/nvidia/pytorch:25.11-py3 python pytorch-gpt-soseki.py fp32 300`). Answer on the GB10: eager fp32 0.243 s a step, bf16 autocast 0.21, `torch.compile` + bf16 0.096; the eager 5000-step run 20.4 min. |
| `elementwise-precision.lisp` | phase 4b's OTHER question: how far does a device transcendental sit from the scalar defun, per member and per width? It prints values rather than differences, because the two sides are two RUNS -- run it with `--simd` and with `--simd --gpu` and diff the files with the script in its header. The precision table in `.kb/gpu.md` is that script's output on the JVM class output. Runs anywhere: with no device the two files are identical, which is itself the check that the flag is the only variable. |

### The Metal files (macOS)

| file | question it answers |
| --- | --- |
| `Mtl.java` | the binding itself: Metal through `objc_msgSend` over pure FFM, no Swift shim and no bundled dylib. Every `Mtl*` file uses it. The CUDA side's `Cu.java`, one platform over. |
| `MtlF64Probe.java` | the decisive one, and it is not about speed: does MSL have a `double`? Also `half` / `bfloat` / 64-bit int. |
| `MtlSpike.java` | does a Metal matmul beat the CPU, and where is the crossover? Holds `SRC`, the MSL the other probes compile. |
| `MtlTiny.java` | the fixed per-call floor -- and, because Metal's floor is 5x CUDA's, whether spinning on the command buffer's status beats `waitUntilCompleted`, and how much batching several dispatches into ONE command buffer amortizes. |
| `MtlResidency.java` | must arrays LIVE on the device? Measures three ways, not two, because Metal has two separate costs to remove. Plus the batched rank-3 product. |
| `MtlPrecision.java` | the precision contract: how far f32 lands from the f64 scalar oracle on inputs that do NOT round-trip exactly, and whether MSL's default compile options are doing fast-math behind our back. |
| `MtlMps.java` | the cuBLAS question, re-asked where the tuned library is IN THE OS: is `MPSMatrixMultiplication` worth using? |
| `MtlMpsDiff.java` | verifies the surprising half of that answer -- MPS and the naive tiled kernel are bit-identical, which a silent no-op would also look like. |
| `MtlCompileCost.java` | the PTX question restated: what does getting a kernel onto the device cost at startup, and does the OS cache it between processes? |
| `MtlNiProbe.java` | does an `objc_msgSend` downcall survive GraalVM native-image next to `-H:+VectorAPISupport`? |
| `MtlPhase5.java` | PHASE 5's own probe, and the only Metal file that compiles the CHECKED-IN `src/main/resources/am/ik/gpu/gemm.metal` rather than a string of its own: a syntax error or a missing MSL builtin fails here rather than at run time. Also checks every kernel against a Java oracle and measures the crossovers that set this backend's thresholds. |
| `MtlBreakdown.java` | where the per-call microseconds GO, which is the question phase 5's design turned on: MPS object creation against a cached one, and a fresh `MTLBuffer` against a pooled one -- the measurement that made a buffer pool mandatory. Also checks that `rowBytes = columns * 4` is accepted by MPS on a shape whose `rowBytesFromColumns:` pads. |
| `MtlMatvecCrossover.java` | `MatvecCrossover`'s question on Metal (todo-477): where does a resident GEMV beat the `--simd` lane kernel on this machine, does the cold trip ever pay (no: it is a memcpy of the bytes the CPU would have streamed), and -- MSL having no `double` -- what accumulator lands on the scalar defun's bits? Three device columns per shape (cold / resident / kernel) over two accumulators, a plain float sum and a COMPENSATED float-float pair, the latter compiled with and without `#pragma METAL fp contract(off)`; then both against the double-accumulated oracle at 1024x768. Answer (`.kb/gpu.md`, "Residency and the GEMV on this backend"): the compensated pair is bit-identical on 1024 of 1024 rows where the float sum is on 229, at no cost; resident wins from 2^21; cold never. Its CPU column is `matvec-baseline.lisp` under `--simd`, run on the same machine. |
| `MtlGemvInSitu.java` | the SHIPPED route (`Gpu.matvec` over `target/classes`, residency and pool included) as a program calls it: the per-call distribution back to back, and the cost of the same resident call after a CPU gap of 0-10 ms -- the decode-loop question. Answer: a resident head is ~205 us median back to back and ~800 after a 2.5 ms gap, because the GPU lowers its clocks after ~1 ms idle; which is why `examples/llama2` is 1.0x on this machine with the flag. Run from the repository root with `-cp target/classes`. |
| `MtlSoftF64.java` | todo-494's bit-identity question on a backend with no `double`: every resident-tier member over 262144 bit patterns (subnormals, the specials, the tiny and the huge) and 60-odd scalars, against Java's arithmetic bit for bit -- the scalar forms over a non-float scalar, the Adam update and the sum fold through `gemm.metal`'s software binary64, the float routes through the flush guard. Answer: 0 mismatches. Found on the way that this GPU flushes subnormal floats to zero in EVERY float operation under `MTLMathModeSafe` (hence the guard), and that plain `sqrt` is 1 ulp off in ~10% of operands where `precise::sqrt` is correctly rounded. Run from the repository root with `-cp target/classes -Dam.ik.gpu.metal.residentMin=1`. |
| `MtlResidentFloor.java` | todo-494's threshold question: a resident-operand launch (no copy) against the CPU's memcpy + lane loop, 2^10 .. 2^21 elements. Answer: ~100-140 us a call at every size below 2^18, crossing the CPU between 2^18 and 2^19 -- and the training step still measured fastest with `MIN_RESIDENT_ELEMENTS` at 2^14, because a declined member costs a materialize and a re-upload around it (`.kb/gpu.md`, "Lazy results and the resident tier on Metal"). Same invocation. |
| `MtlStridedFloor.java` | todo-509's question: what does the strided tier's LAYOUT cost on this backend, where it was a 4 KB pooled slab per `bcast`/`gather`/`where`/`copy` call for 96-256 bytes of ints? Per-call cost through the shipped route at the sizes the tier runs at (`MIN_STRIDED_ELEMENTS` is 2^18 here), plus the small end -- a strided `copy` over a RESIDENT operand, which runs from 2^14. Answer (`.kb/gpu.md`, "The strided layout cost a pooled slab here, not a copy"): a wash at every size, because the smallest call the tier takes is a memory pass over 2^18 elements (242 us) and what was removed is a deque pop, a binding and a push -- the layout moved for its COUNT (724 pooled slabs a training step, a sixth of every acquisition the pool serves) and not for its time. Run from the repository root with `-cp target/classes`; pair a run before the change with one after. |
| `MtlPerRowMap.java` | todo-642's question: `MIN_MAP_ELEMENTS` is 2^17 and was measured against `sin` over a WHOLE array, but what straddles it is a chain's per-row intermediate -- the `rows x 1` operand of `log-softmax`'s `(linalg:log (linalg:sum ... :keepdims t))`, 16384 elements at the book's shapes. Three columns per size, 2^12 .. 2^18: the CPU's `(float) Math.log` loop, the device map back to back, and the device map behind a CPU gap -- the third because this backend REFUSES the axis fold at every size, so the `sum` that writes this operand is a CPU loop 28-30 ms long and the GPU has been idle for all of it. Answer (`.kb/gpu.md`, "The map threshold at the straddling shape"): the CPU wins at the book's 16384 by 1.5-2.2x back to back and by 6-8x behind the gap; the back-to-back crossover is near 2^15 and the gap one is 2^17..2^18, which is where the threshold already is -- so the threshold holds and the log-softmax straddle stays. The device column needs the constant opened up: run it against a COPY of `am.ik.gpu` whose `MetalGemm.MIN_MAP_ELEMENTS` reads `Long.getLong("probe.mapMin", 1L << 17)`, `java -cp probe-classes:target/classes -Dprobe.mapMin=1024`. |
| `gpt-book-shapes-fast.lisp` | (moved 2026-11-18 to the `llm-from-scratch` project's `bench/`, which holds the library it loads) the chapter-3 GPT at the BOOK's shapes (`d_model` 384, block 256, 6 layers, 6 heads, batch 64) over a synthetic 36456-character corpus of the novel's 3038-character vocabulary, with `*max-steps*` from the `STEPS` environment variable and, since todo-499, the batch from `BATCH` (the step's graph is tens of gigabytes at 64; a machine sharing its memory runs 32). The per-step rows of every Metal table since todo-494 are taken on it: the novel itself costs six minutes of data-loader setup a run and the step does not depend on the corpus, so the step is `(t13 - t3) / 10`. Not a probe -- a rontolisp program; compile it to a class and run that. |
| `fusion-baseline.lisp` + `fusion-segments.py` | (2026-09-02, todo-499) the per-member DEVICE cost of the fusible compositions at the book's shapes: every `linalg:` member of the softmax / layer-norm / GELU / dropout chains, the chains themselves, and the torch forward and forward+backward through each, as kernel time read back from an nsys trace -- the program opens every bench with a marker launch (an `rng_fill` over 1234567 elements) and syncs every call, and the script cuts the trace at the markers. Wall time is useless here (a call that allocates a fresh 100 MB result spends its host time in the allocator, and the clock has 1 ms resolution), which is why the first version of this probe, timed by `get-internal-real-time`, reported a GELU forward at 86 ms and its forward+backward at 59. Run it once per build and pair the tables: the numbers in `.kb/gpu.md` "The fused tier" are its before and after. |
| `chains-baseline.lisp` (+ `fusion-segments.py`) | (2026-09-02, todo-629) the per-member DEVICE cost of the chains the fused tier left composed, at the book's shapes and BATCH 64: the attention scale and mask around each softmax, every member of the log-softmax chain and its adjoint over a `(16384 3038)` batch of logits, layer-norm's affine, and the GELU adjoint against its own bandwidth floor. Same marker-and-sync method as `fusion-baseline.lisp`, read back by the same script. One thing it must do that the older probe did not: the SCALAR tier is offered over a resident operand only, so its operands are the results of a device member rather than host arrays -- a host array there silently measures the CPU and every bench reads 0 launches. |
| `Bf16MatvecCrossover.java` + `matvec-bf16-baseline.lisp` | (2026-09-06, todo-490) the bfloat16 GEMV through the SHIPPED route (`Gpu.matvec(short[], ...)` over `target/classes`, residency included): bf16 resident against f32 resident against bf16 cold, shape by shape from 384x384 to Qwen3.5-0.8B's 248320x1024 head, and at every shape the equivalence the kernel promises -- the bf16 result against the f32 kernel over the widened matrix, in mismatching rows (0 everywhere). `matvec-bf16-baseline.lisp` under `--simd` on the JVM class output is the CPU column at both widths, under Graal and C2. Answer (`.kb/gpu.md`, "The GEMV, and the matrix that stays"): the device floor is ~10 us at either width, bf16 is 1.9-2.9x the f32 kernel from 12 MB up once the accumulator was fixed (below), and the 2^17 threshold stands -- with the f32 tie at exactly 2^17 recorded. Run from the repository root with `-cp target/classes`. |
| `Bf16KernelProbe.java` + `gemv-bf16-probe.cu` | (2026-09-06, todo-490) the measurement that changed the shipped kernels: kernel-only time over resident buffers for the bf16 GEMV at one, two, four and eight patterns per lane -- all at the same ~138 GB/s, so the loads were not the bound -- then the same kernels with a plain float and a COMPENSATED float-float accumulator: the compensated pair at 232 GB/s (bf16) and bit-identical to the double-accumulated oracle on every row at seven shapes, the plain float sum off on most rows. The double FMA per element is a compute ceiling on the GB10 (~70 G/s, its fp64 rate), which the f32 kernel sat just under and the bf16 kernel hit. Needs `nvcc` once, for its own PTX (command in the `.cu` header). |
| `ResidencyCliff.java` | (2026-09-06, todo-490 step 6) what a decode loop does when the model does NOT fit the residency budget: runs a program under a forced budget through the package-private `residentBudget` seam (reached reflectively in whichever copy of the library the run has) and lets the program print its tok/s, announcing the budget in force as soon as one is derived and the resident bytes, hit/miss, eviction and re-upload counts every five seconds on stderr. Since todo-716 it drives BOTH halves of the flag -- it looks for the class output's renamed `RontoLispGpuGpu` and for the interpreter's own `am.ik.gpu.Gpu`, so passing the exec jar and `am.ik.rontolisp.cli.RontoLispCli` runs the CLI under the same forced budget (usage in the file header). Answer: under the interceptors the budget is the lazy HEADROOM rule (the device less an eighth), so a 1.5 GB model never meets the 1 GB eager cap; forced below the model, the tokens degrade to the CPU rate and no further (the numbers: `examples/llm/README.md`, "bf16 weights on the device"). |
| `decode-per-token.py` | (2026-09-06, todo-718) where a DECODE STEP goes on the device side: buckets an `nsys` sqlite export of an `examples/llm` run per forward pass -- the classifier-head launch is the boundary -- into kernel time by grid, copies by size and CUDA API time on the calling thread, medians over the steady forwards plus the whole timeline (usage in the header). Answer for Qwen3.5-0.8B at bf16: 7.5 ms of kernels and 11.9 ms of driver calls in a 45 ms forward, 102 MB of KV cache going up every token. Python, no GPU code of its own. |
| `decode-jfr-agg.py` | (2026-09-06, todo-718) the HOST side of the same step: aggregates a JFR recording of the run (record at ONE thread, print with `--stack-depth 64`) by category -- residency guards, driver waits, lane kernels, Lisp loops -- and by the innermost Lisp function. Answer: under the flag 40% of the main thread's decode window is `materialize` / `written`, called per store from the typed loop over the DeltaNet state, so the three mixer functions cost ~30 ms a forward against 6.8 without the flag. Not a GPU program. |
| `gemv-q4-probe.cu` + `Q4KernelProbe.java` | (2026-09-07, todo-726) the Q4 ceiling MEASURED instead of scaled from bytes: Q4_0 and Q8_0 GEMV kernels over ggml's own block layouts (16-bit loads, `L` lanes a block; a `_split` re-pack as the layout upper bound; `_dp` = the integer-dot shape over a Q8-quantized activation, `__dp4a`) against the shipped `gemv_bf16` / `gemv_f32`, as device-side kernel time (CUDA events) cold from DRAM -- the launch rotated over >= 256 MB of copies, since a decode step streams the whole model through the 24 MB L2 every token -- at the seven shapes a Qwen3.5-0.8B forward launches and with their counts, so the last table is GEMV ms a FORWARD at each width. Answer: bf16 7.6 / 7.0 ms (cold median / min; in situ 6.74), Q8_0 0.50-0.58 of it, Q4_0 0.24-0.33; the f32-x kernels lose to the x-side stride, the `_dp` ones reach 200-220 GB/s at the head. The write-up is "The Q4 ceiling, measured" below. |
| `gemv-q8-exact-probe.cu` + `Q8ExactKernelProbe.java` | (2026-09-07, todo-728) the Q8_0 GEMV that is the CPU CONTRACT's bits (`.kb/quantized-matrix.md`: the activation quantized per block in double, four exact integer lane sums a block, four f32 accumulators walked over the blocks in order, folded `(acc0 + acc2) + (acc1 + acc3)`), as six candidate kernels each pinned BIT FOR BIT against a Java transcription of the contract at every shape, timed by `Q4KernelProbe`'s cold method against the shipped `gemv_bf16` and `gemv-q4-probe.cu`'s `gemv_q8_0_dp4` (a warp fold, NOT the contract's bits), plus a device-side activation quantizer timed alone and back to back with the GEMV. Answer: eight threads a row with one lane a thread (`_b8`) at 5.3 ms of GEMV a forward against bf16's 7.7 and the free fold's 4.6; four threads a row is starved, a 4x4 transpose-reduce costs 15% more, `__dp4a` buys nothing, and a quantize launch of its own costs +4.2 us on the stream -- so the host quantizes (0.7-2.5 us a call). Shipped as `gemm.cu`'s `gemv_q8_0`; the write-up is "The exact Q8_0 kernel, measured" below. |
| `AccelerateProbe.java` | no GPU at all: a tuned BLAS is plain C, costs no dependency, and unlike Metal it has a double. How fast is it, is one PRESENT, and is the one that is present actually TUNED? Walks a candidate list (Accelerate, NVPL, OpenBLAS, MKL, distro `libblas`), identifies what it bound and prints a verdict against measured throughput. Runs on either machine -- the probe that reframes the Apple plan, and the one that stopped it being reframed the same way on Linux. |

## Running them

```bash
cd .todo/artefacts/123-gpu-acceleration

# the CPU baseline to beat (rontolisp's fastest path today)
JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline.lisp -o Mm2.class --simd   # keep the -o name path-free
java --add-modules jdk.incubator.vector Mm2

# the GPU probes
java --enable-native-access=ALL-UNNAMED MatmulSpike.java
java --enable-native-access=ALL-UNNAMED TinySpike.java
java --enable-native-access=ALL-UNNAMED ResidencySpike.java
java --enable-native-access=ALL-UNNAMED Tf32Check.java      # needs libcublas (toolkit)
java --enable-native-access=ALL-UNNAMED CublasEndToEnd.java # needs libcublas (toolkit)

# the width probes -- no GPU involved, Vector API only
java --add-modules jdk.incubator.vector MatmulFProbe.java
java -jar $JAR width-baseline.lisp -o W.class --simd && java --add-modules jdk.incubator.vector W

# PtxSpike needs the PTX first; 75 is CUDA 13's oldest accepted virtual arch
java --enable-native-access=ALL-UNNAMED DumpPtx.java 75
java --enable-native-access=ALL-UNNAMED PtxSpike.java
```

The three `am.ik.gpu` probes need the driver and nothing else -- no toolkit, because they
load the kernels the library ships rather than compiling any. They are the ones whose
numbers `.kb/gpu.md` quotes, so they are the ones to re-run on new hardware:

```bash
java --enable-native-access=ALL-UNNAMED AllocatorCost.java
java --enable-native-access=ALL-UNNAMED CopyRoute.java
java --enable-native-access=ALL-UNNAMED WorthCrossover.java
java --enable-native-access=ALL-UNNAMED ZeroCopyRoute.java
java --enable-native-access=ALL-UNNAMED ResidencyCost.java        # and: ResidencyCost.java keep
java -Xmx6g --enable-native-access=ALL-UNNAMED FreshPageCost.java
(cd ../../.. && java --enable-native-access=ALL-UNNAMED -cp target/classes .todo/artefacts/123-gpu-acceleration/RngCrossover.java)
nvcc -arch=compute_75 -ptx matvec-probe.cu -o matvec-probe.ptx && java --enable-native-access=ALL-UNNAMED MatvecCrossover.java
java -jar $JAR matvec-baseline.lisp -o Mv.class --simd && java --add-modules jdk.incubator.vector Mv

# the CPU column WorthCrossover has to be read against, JIT-warm
JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline-warm.lisp -o Mm3.class --simd
java --add-modules jdk.incubator.vector Mm3
```

`CuLib.java` is picked up automatically by the source launcher, the same rule as `Cu.java`.

`Cu.java` and `MatmulSpike.java` are picked up automatically by the source launcher --
do not pass them as arguments, or they land in `args` instead (that mistake is why
`DumpPtx` first appeared to reject every `--gpu-architecture`).

### The Metal probes (macOS)

```bash
cd .todo/artefacts/123-gpu-acceleration

# the CPU baseline to beat, same two programs as the CUDA side
JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline.lisp -o Mm2.class --simd   # keep the -o name path-free
java --add-modules jdk.incubator.vector Mm2

java --enable-native-access=ALL-UNNAMED MtlF64Probe.java
java --enable-native-access=ALL-UNNAMED MtlSpike.java
# the two phase-5 probes read the checked-in kernels, so run them from the REPO ROOT:
#   java --enable-native-access=ALL-UNNAMED .todo/artefacts/123-gpu-acceleration/MtlPhase5.java
#   java --enable-native-access=ALL-UNNAMED .todo/artefacts/123-gpu-acceleration/MtlBreakdown.java
java --enable-native-access=ALL-UNNAMED MtlTiny.java
java --enable-native-access=ALL-UNNAMED MtlResidency.java
java --enable-native-access=ALL-UNNAMED MtlPrecision.java
java --enable-native-access=ALL-UNNAMED MtlMps.java
java --enable-native-access=ALL-UNNAMED MtlMpsDiff.java
java --enable-native-access=ALL-UNNAMED AccelerateProbe.java
java --enable-native-access=ALL-UNNAMED MtlCompileCost.java   # run it three times
java -jar $JAR matvec-baseline.lisp -o Mv.class --simd && java --add-modules jdk.incubator.vector Mv
java --enable-native-access=ALL-UNNAMED MtlMatvecCrossover.java
(cd ../../.. && java --enable-native-access=ALL-UNNAMED -cp target/classes .todo/artefacts/123-gpu-acceleration/MtlGemvInSitu.java)
(cd ../../.. && java --enable-native-access=ALL-UNNAMED -Dam.ik.gpu.metal.residentMin=1 -cp target/classes .todo/artefacts/123-gpu-acceleration/MtlSoftF64.java)
(cd ../../.. && java --enable-native-access=ALL-UNNAMED -Dam.ik.gpu.metal.residentMin=1 -cp target/classes .todo/artefacts/123-gpu-acceleration/MtlResidentFloor.java)
(cd ../../.. && java --enable-native-access=ALL-UNNAMED -cp target/classes .todo/artefacts/123-gpu-acceleration/MtlStridedFloor.java)

# the book's shapes, per step: compile once, run at two step counts, diff
(cd ../../.. && java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
   <llm-from-scratch project>/bench/gpt-book-shapes-fast.lisp -o /tmp/b/BenchBook.class --gpu --simd)
(cd /tmp/b && STEPS=3  java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx64g BenchBook)
(cd /tmp/b && STEPS=13 java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xmx64g BenchBook)
```

`Mtl.java` and `MtlSpike.java` are picked up automatically, same rule as above. None of
these needs Xcode, a toolchain, or a build step -- which is the point of
`MtlCompileCost`.

The native-image leg is the same recipe with the Apple classes:

```bash
javac -d classes MtlNiProbe.java Mtl.java MtlSpike.java
java --enable-native-access=ALL-UNNAMED \
     -agentlib:native-image-agent=config-output-dir=ni-config-mtl -cp classes MtlNiProbe
mkdir -p classes/META-INF/native-image/spike-mtl
cp ni-config-mtl/reachability-metadata.json classes/META-INF/native-image/spike-mtl/
native-image --no-fallback --enable-native-access=ALL-UNNAMED \
             --add-modules jdk.incubator.vector \
             -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport \
             -cp classes MtlNiProbe mtlniprobe
./mtlniprobe
```

The agent's `foreign.downcalls` section here carries an entry the CUDA side never needed:
`{"returnType": "void", "parameterTypes": ["void*", "void*", "struct(jlong,jlong,jlong)",
"struct(jlong,jlong,jlong)"]}` -- `dispatchThreadgroups:threadsPerThreadgroup:` taking two
`MTLSize`s by value. It built in 19.3 s and ran:

```
MtlNiProbe OK on Apple M4 Max: n=512 f32 gemm 0.864 ms, C[0]=-0.938
```

Same answer as the JVM, so Metal does not re-enter the `VectorAPISupport` /
`SharedArenaSupport` fight either.

### The native-image leg

`NiProbe` is the one that needs a real build. Foreign downcalls must be registered, and
the tracing agent writes the registration for you:

```bash
javac -d classes NiProbe.java Cu.java MatmulSpike.java
java --enable-native-access=ALL-UNNAMED \
     -agentlib:native-image-agent=config-output-dir=ni-config -cp classes NiProbe
mkdir -p classes/META-INF/native-image/spike
cp ni-config/reachability-metadata.json classes/META-INF/native-image/spike/
native-image --no-fallback --enable-native-access=ALL-UNNAMED \
             --add-modules jdk.incubator.vector \
             -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport \
             -cp classes NiProbe niprobe
./niprobe
```

The `--add-modules` / `-H:+VectorAPISupport` pair is not incidental: it reproduces the
real binary's build flags, which is the whole point -- todo-102 found `VectorAPISupport`
and `SharedArenaSupport` mutually exclusive, and this proves CUDA does not re-enter that
fight. Without the metadata the binary fails at the FIRST downcall with
`MissingForeignRegistrationError`; with it:

```
NiProbe OK on NVIDIA GB10: n=512 f64 gemm 0.585 ms, C[0]=638.000
```

## What the `am.ik.gpu` probes printed (2026-08-20, GB10)

These three are the evidence behind `.kb/gpu.md`, and each of them corrects something the
CUDA spike above got wrong.

```
$ java AllocatorCost.java
NVIDIA GB10, 48 SMs, checked-in compute_75 PTX loaded in 8.87 ms

-- one allocate + free pair, in isolation --
         4096 B   cuMemAllocAsync   2.26 us | cuMemAlloc   138.82 us  (62x)
      1048576 B   cuMemAllocAsync   0.67 us | cuMemAlloc   136.24 us  (203x)
      8388608 B   cuMemAllocAsync   0.66 us | cuMemAlloc   336.24 us  (513x)

-- a whole f64 product, both allocators, three buffers a call --
    n           pooled us    unpooled us      ratio    per pair us
    64               26.4          181.5        6.9           51.7
    128              42.5          209.4        4.9           55.6
    256             125.7          302.7        2.4           59.0
    512             692.2         1203.2        1.7          170.3

-- what a FAILED pooled allocation costs the device --
    free before                              117774 MB
    two 67079 MB requests: second -> CUresult 2
    free with the first still held            49350 MB
    free after cuMemFreeAsync, no trim        49350 MB
    free after cuMemPoolTrimTo, NO sync       49350 MB
    free after cuCtxSynchronize + trim       117787 MB
```

Three separate results. **The allocator is the floor**: `cuMemAlloc` is 62-513x a pooled
allocation in isolation, and three of them per product is the difference between a 26 us
call and a 181 us one -- so the spike's "16-18 us floor" was only ever true of a loop that
allocated once, and a real per-call intercept has to use `cuMemAllocAsync`. Note that the
isolated 137 us pair and the ~52-59 us the same pair costs inside a steady product loop are
DIFFERENT measurements; the floor comes from the product column, not from multiplying the
isolated one by three. **And a failed pooled allocation is not free**: it grows the pool on
the way to failing, `cuMemFreeAsync` is stream-ordered so the buffers are not back yet, and
a trim issued before `cuCtxSynchronize` finds them still in use and returns success having
done nothing. All three lines are needed, in that order, or a declined product silently
holds 68 GB for the life of the process.

```
$ java CopyRoute.java
-- one f64 product end to end, allocation included --
    n             staged us      critical us    ratio
    8                  26.1             21.0     1.24
    32                 23.3             19.6     1.19
    64                 31.3             20.9     1.49
    128                72.1             40.9     1.76
    256               228.2            128.1     1.78
    512              1055.3            693.4     1.52
    1024            15462.5           4856.6     3.18
    2048            75286.9          36878.7     2.04

-- how long one CRITICAL call holds the thread off a safepoint --
    n           MB/op     HtoD crit us    DtoH after launch    DtoH after sync
    128           0.1              7.7                 21.6                7.5
    256           0.5             14.5                 93.2               13.4
    512           2.1             41.4                608.0               40.7
    1024          8.4            141.8               4564.9              141.7
    2048         33.6            569.9              35721.6              547.8
    4096        134.2           2261.3             283241.9             2243.1
```

The first table kills todo-123's "the heap copy is unavoidable in every row": a critical
downcall takes the heap array directly, and the gap WIDENS with size rather than closing,
because the staging buffer is a native allocation of the operand's size on every call. The
second table is the GPU-only hazard: compare the last two columns. A device-to-host copy
issued straight after a launch is not a copy, it is a wait -- 283 ms of it at n=4096 --
and putting `cuCtxSynchronize` in front of it costs 0.26 us and gives back a window bounded
by bytes.

```
$ java WorthCrossover.java
n           f64 us/call    f32 us/call     n*m*p
16                 18.3           17.6     4096
32                 18.2           16.1     32768
40                 19.0           15.8     64000
48                 19.2           16.5     110592
56                 20.3           18.2     175616
64                 21.3           16.7     262144
96                 36.8           19.3     884736
128                42.8           21.9     2097152
256               123.9           51.0     16777216
512               692.1          183.3     134217728

$ java --add-modules jdk.incubator.vector Mm3   # matmul-baseline-warm.lisp
n=32 f64 9.50 us   n=32 f32 6.25 us
n=40 f64 11.75 us  n=40 f32 7.50 us
n=48 f64 23.00 us  n=48 f32 14.00 us
n=56 f64 34.50 us  n=56 f32 20.25 us
n=64 f64 49.00 us  n=64 f32 28.50 us
n=96 f64 131.00 us n=96 f32 73.50 us
n=128 f64 383.75 us n=128 f32 191.00 us
```

Read the two together: the crossover is n~45 at f64 and n~51 at f32, so `am.ik.gpu` sets
`worth()` at `n*m*p >= 2^17`. The `--simd` column in the deleted item is the
same program with 3 warm-ups and 20 reps, which reports 100 us at n=32 where a warm run
costs 9.5 -- about 10x, and all of it at exactly the sizes the threshold is decided by.

## What they printed

Recorded verbatim so a later run can be diffed against them. The interpretation of each
table is in `../../../.kb/gpu.md`; this is only the raw evidence.

```
$ java MatmulSpike.java
device: NVIDIA GB10  sm_121  SMs=48 unified-addr=1 managed=1
nvrtc compile+load: 145.6 ms
n=64    cpu(java f64)     0.65 ms | gpu f64   0.043 ms w/copy,   0.025 ms kernel | gpu f32   0.036 /   0.020 | f64 exact-vs-cpu maxdiff 2.28125
n=128   cpu(java f64)     1.52 ms | gpu f64   0.072 ms w/copy,   0.029 ms kernel | gpu f32   0.037 /   0.016 | f64 exact-vs-cpu maxdiff 1.90625
n=256   cpu(java f64)    11.07 ms | gpu f64   0.198 ms w/copy,   0.088 ms kernel | gpu f32   0.088 /   0.026 | f64 exact-vs-cpu maxdiff 1.8125
n=512   cpu(java f64)   142.25 ms | gpu f64   0.906 ms w/copy,   0.576 ms kernel | gpu f32   0.320 /   0.120 | f64 exact-vs-cpu maxdiff 2.6875
n=1024  cpu(java f64)  1245.76 ms | gpu f64   5.720 ms w/copy,   4.432 ms kernel | gpu f32   1.625 /   0.859 | f64 exact-vs-cpu maxdiff 1.5625
n=2048  cpu(java f64) 16346.68 ms | gpu f64  40.541 ms w/copy,  35.238 ms kernel | gpu f32   9.360 /   6.740 | f64 exact-vs-cpu maxdiff 1.5

-- element-wise add (memory bound), f64 --
n=4096      cpu   0.037 ms | gpu   0.023 ms w/copy |   0.008 ms kernel
n=65536     cpu   0.513 ms | gpu   0.112 ms w/copy |   0.008 ms kernel
n=1048576   cpu   7.419 ms | gpu   1.579 ms w/copy |   0.041 ms kernel
n=16777216  cpu   9.003 ms | gpu  23.467 ms w/copy |   1.667 ms kernel

-- pure launch overhead (16x16 gemm, sync each) --
launch+sync 8.1 us | launch only (async) 3.7 us
```

The `cpu(java f64)` column is a plain triple loop and is NOT JIT-warm at the small end,
so it flatters the GPU there. `matmul-baseline.lisp` is the honest CPU number and the
one the todo quotes; the element-wise `n=16777216` row is the useful negative result --
128 MB of operands, and the copies lose to the CPU outright.

```
$ java PtxSpike.java
(1) driver JIT of compute_75 PTX onto sm_121: 25.9 ms -- no nvrtc, no toolkit

(2) memory routes, n=1024 f64 gemm
    device alloc + HtoD/DtoH (double copy: heap->native->device)   6.028 ms  [C[0]=0.219]
    cuMemAllocManaged (one copy: heap->managed, GPU reads it)       5.546 ms  [C[0]=0.219]
    ... same buffers, already resident (kernel only)                4.441 ms
    pinned host + DEVICEMAP (GPU reads host memory directly)        5.450 ms  [C[0]=0.219]

(3) cuBLAS comparison
    n=1024  f32: cuBLAS   0.127 ms (16891.1 GFLOP/s) | tiled PTX kernel   0.860 ms (6.8x)
    n=1024  f64: cuBLAS   5.116 ms ( 419.7 GFLOP/s) | tiled PTX kernel   4.432 ms (0.9x)
    n=2048  f32: cuBLAS   0.918 ms (18719.9 GFLOP/s) | tiled PTX kernel   6.708 ms (7.3x)
    n=2048  f64: cuBLAS  40.866 ms ( 420.4 GFLOP/s) | tiled PTX kernel  35.183 ms (0.9x)
```

Line (1) is the single most important line in this directory: `compute_75` PTX, six
generations older than the card, JIT-compiled by the driver alone. That is what lets the
feature ship without a CUDA toolkit on the user's machine. 25.9 ms is the COLD number;
the driver keeps its own on-disk compute cache (`~/.nv/ComputeCache`), so a re-run of
the same PTX reports **1.3 ms**. Since the resource is a fixed, checked-in text, every
run after a user's first one is the cached one -- so the load cost is a startup
non-issue and needs no `cuModuleLoadDataEx` cache plumbing of our own.

```
$ java Tf32Check.java
cublasGetMathMode = 0  (0 = CUBLAS_DEFAULT_MATH, 1 = TF32_TENSOR_OP, 3 = PEDANTIC)
input   1+2^-20 = 1.000000954 (bits 3f800008)
cuBLAS  C[0]    = 1.000000954 (bits 3f800008)
=> low bit SURVIVED: genuine FP32, not TF32
```

```
$ java CublasEndToEnd.java
f32  (ms/call)
    n     ours+copy  cuBLAS+copy  ratio |  ours res.  cuBLAS res.  ratio
    256       0.080        0.067    1.2x |     0.030        0.017    1.8x
    512       0.269        0.184    1.5x |     0.121        0.034    3.6x
    1024      1.404        0.672    2.1x |     0.861        0.127    6.8x
    2048      8.799        2.970    3.0x |     6.752        0.927    7.3x
f64  (ms/call)
    n     ours+copy  cuBLAS+copy  ratio |  ours res.  cuBLAS res.  ratio
    256       0.167        0.206    0.8x |     0.087        0.125    0.7x
    512       0.858        0.990    0.9x |     0.576        0.707    0.8x
    1024      5.433        6.143    0.9x |     4.433        5.116    0.9x
    2048     39.579       44.922    0.9x |    35.243       40.875    0.9x
```

This is the probe that settles the cuBLAS question, and it settles it against cuBLAS:
at f64 -- the default `linalg` width -- the naive tiled kernel is 10-25% FASTER, and at
f32 the famous 7x shrinks to 1.2-3.0x once the copies phase 1 cannot avoid are on the
clock. Against that: `libcublas.so.13` is 59 MB and links `libcublasLt.so.13` at 601 MB
(`ldd` confirms the link), so the price is a 660 MB toolkit requirement.

```
$ java ResidencySpike.java
-- batched rank-3 matmul, the shape --simd never intercepts --
    b*h=24   n=64   d=32   java f64      27.2 ms | gpu   0.025 ms (1078x, 249 GFLOP/s)
    b*h=48   n=256  d=64   java f64     350.4 ms | gpu   0.187 ms (1873x, 2152 GFLOP/s)
    b*h=192  n=512  d=64   java f64    2778.3 ms | gpu   2.679 ms (1037x, 2405 GFLOP/s)

-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2) --
    n=128   resident (1 up, 5 kernels, 1 down)   0.051 ms | per-op round trip   0.192 ms (3.7x worse)
    n=512   resident (1 up, 5 kernels, 1 down)   0.369 ms | per-op round trip   1.012 ms (2.7x worse)
    n=1024  resident (1 up, 5 kernels, 1 down)   2.218 ms | per-op round trip   4.441 ms (2.0x worse)
```

The `java f64` column here is an unwarmed single-batch loop scaled by the batch count --
an order of magnitude, not a measurement. The right-hand pair is the real result, and it
is what demoted residency from precondition to phase 3.

```
$ java TinySpike.java
one intercepted linalg:matmul, host->device->kernel->host, f64:
      8x8   @   8x8      18.0 us
     32x8   @   8x8      16.4 us
     32x32  @  32x32     19.7 us
     64x64  @  64x64     23.7 us
    128x128 @ 128x128    56.0 us
```

```
$ java --add-modules jdk.incubator.vector Mm2     # matmul-baseline.lisp, JVM --simd
                            BEFORE todo-469          AFTER todo-469 (5a3e8f16)
n=32   f64 / f32            0.150 / 0.050            0.100 / 0.100
n=64   f64 / f32            0.450 / 0.400            0.450 / 0.400
n=128  f64 / f32            0.500 / 0.750            1.400 / 0.850
n=256  f64 / f32            2.800 / 5.200            2.800 / 1.450
n=512  f64 / f32           21.200 / 39.800          21.400 / 10.900
```

The f32 column moved 3.7x at n=512 and the widths inverted, so the todo quotes the AFTER
column and the GPU's f32 margin there is 34x rather than the 124x the BEFORE column
implied. `#d` did not move, as designed.

Note what the last two blocks say together: the GPU's ~16-18 us floor is flat, and
`--simd` costs 0.1-0.45 ms at n=32-64 -- so below n~64 the GPU is not beating CPU
arithmetic, it is beating rontolisp's own per-call overhead. That win is real but
fragile, and it is why the size threshold must be measured on the target machine rather
than hardcoded from FLOP counts.

```
$ java --add-modules jdk.incubator.vector MatmulFProbe.java     # random zero-mean inputs
f32 lanes=4, f64 lanes=2
      laneF32 vs oracle: max 701460 ulp, max rel 0.0428
n=256  scalarAcc   5.06 ms | laneF2D  930.74 ms (bit-identical) | laneF32   1.37 ms (DIFFERS) | f64   2.49 ms
      laneF32 vs oracle: max 440342 ulp, max rel 0.0310
n=512  scalarAcc  39.01 ms | laneF2D 7477.00 ms (bit-identical) | laneF32  10.38 ms (DIFFERS) | f64  19.52 ms

$ java --add-modules jdk.incubator.vector W                     # width-baseline.lisp, JVM --simd
matmul n=256 f64 2.550   f32 5.100      <- the anomaly: f32 2x SLOWER
matmul n=512 f64 20.250  f32 39.850
add   1d f64 1.100       f32 0.620      <- everything else behaves
exp   1d f64 2.420       f32 2.100
dot   1d f64 0.240       f32 0.280
sum   1d f64 0.280       f32 0.280
```

`scalarAcc` is today's kernel, `laneF2D` is the wasm backend's approach ported to the
JVM, `laneF32` is f32 lanes with an f32 accumulator. Two results decided todo-469:
`convert(F2D)` is **190x** slower than the scalar loop it would replace, so wasm's
bit-identical trick cannot come to the JVM; and `laneF32` is 2.8x faster than the f64
kernel but differs from the oracle by up to 3-4% relative on the worst (near-zero,
post-cancellation) cell. The probe's f64 column reproduces rontolisp's own 20.25 ms, so
it is measuring the right kernel. Run it with the DYADIC inputs it originally had and
`laneF32` reports "bit-identical" -- an artifact of test data that round-trips exactly,
which is why the committed version uses zero-mean random values.

## What the Metal probes printed

Same rule: verbatim, so a later run can be diffed against it. Apple M4 Max, macOS 26.3.1.
The rontolisp `--simd` baseline these are measured against, on the SAME machine, is at the
bottom.

```
$ java MtlF64Probe.java
device: Apple M4 Max
  supportsFamily 1001 (Apple1) = yes
  supportsFamily 1002 (Apple2) = yes
  supportsFamily 1003 (Apple3) = yes
  supportsFamily 1004 (Apple4) = yes
  supportsFamily 1005 (Apple5) = yes
  supportsFamily 1006 (Apple6) = yes
  supportsFamily 1007 (Apple7) = yes
  supportsFamily 1008 (Apple8) = yes
  supportsFamily 1009 (Apple9) = yes
  double             REJECTED: MSL compile failed: program_source:3:28: error: 'double' is not supported in Metal kernel void k(device const double* a, device double* b, uint i [[thread_position_in_grid]]) {                            ^ program_source:3:46: error: 'double' is not supported in Metal kernel void k(device const doub...
  float              COMPILES
  half               COMPILES
  bfloat             COMPILES
  long (64-bit int)  COMPILES

$ java MtlSpike.java
device: Apple M4 Max  unified=1  workingSet=110100 MB  maxTG=1024
MSL compile (newLibraryWithSource): 2.3 ms | pipeline: 1.1 ms
n=64    cpu(java f64)     0.60 ms | gpu f32   0.191 ms w/copy,   0.179 ms kernel | dyadic-input check: matches f64 oracle exactly
n=128   cpu(java f64)     2.46 ms | gpu f32   0.217 ms w/copy,   0.201 ms kernel | dyadic-input check: matches f64 oracle exactly
n=256   cpu(java f64)    10.17 ms | gpu f32   0.270 ms w/copy,   0.260 ms kernel | dyadic-input check: matches f64 oracle exactly
n=512   cpu(java f64)   111.76 ms | gpu f32   0.836 ms w/copy,   0.792 ms kernel | dyadic-input check: matches f64 oracle exactly
n=1024  cpu(java f64)   984.71 ms | gpu f32   1.378 ms w/copy,   1.180 ms kernel | dyadic-input check: matches f64 oracle exactly
n=2048  cpu(java f64) 19435.25 ms | gpu f32   8.832 ms w/copy,   8.070 ms kernel | dyadic-input check: matches f64 oracle exactly

-- element-wise add (memory bound), f32 --
n=4096       cpu   0.027 ms | gpu   0.125 ms w/copy |   0.121 ms kernel
n=65536      cpu   0.421 ms | gpu   0.145 ms w/copy |   0.127 ms kernel
n=1048576    cpu   1.500 ms | gpu   0.369 ms w/copy |   0.149 ms kernel
n=16777216   cpu  13.501 ms | gpu   4.175 ms w/copy |   0.775 ms kernel

-- pure launch overhead (16x16 gemm) --
encode+commit+wait 69.5 us | encode+commit only (async) 6.0 us

$ java MtlTiny.java
one intercepted linalg:matmul, heap->buffer->kernel->heap, f32:
      8x8   @   8x8     113.9 us
     32x8   @   8x8      89.8 us
     32x32  @  32x32     91.7 us
     64x64  @  64x64     84.7 us
    128x128 @ 128x128    84.6 us
    256x256 @ 256x256   119.7 us

wait strategy, empty-ish 16x16 dispatch:
    encode only                   4.4 us
    encode + waitUntilCompleted   81.4 us
    encode + spin on status       79.1 us

N dispatches inside ONE command buffer (16x16 each), us per dispatch:
      1 dispatches:    92.4 us total,  92.38 us each
      2 dispatches:   105.4 us total,  52.69 us each
      5 dispatches:   129.5 us total,  25.89 us each
     10 dispatches:   157.6 us total,  15.76 us each
     50 dispatches:   435.1 us total,   8.70 us each

$ java MtlResidency.java
-- batched rank-3 matmul, the shape --simd never intercepts --
    b*h=24   n=64   d=32   gpu    0.169 ms (37 GFLOP/s)
    b*h=48   n=256  d=64   gpu    0.355 ms (1133 GFLOP/s)
    b*h=192  n=512  d=64   gpu    3.684 ms (1749 GFLOP/s)

-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2), f32 --
    n=128   resident 1 cmdbuf   0.169 ms | resident, 5 cmdbufs   0.615 ms (3.6x) | per-op round trip   0.596 ms (3.5x)
    n=512   resident 1 cmdbuf   0.405 ms | resident, 5 cmdbufs   0.850 ms (2.1x) | per-op round trip   1.118 ms (2.8x)
    n=1024  resident 1 cmdbuf   2.271 ms | resident, 5 cmdbufs   2.766 ms (1.2x) | per-op round trip   3.697 ms (1.6x)

$ java MtlPrecision.java
MTLCompileOptions defaults: mathMode=2  (0=?, 1=safe, 2=relaxed, 3=fast)  fastMathEnabled=1
  default options  n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  default options  n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  default options  tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ
  mathMode=1       n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  mathMode=1       n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  mathMode=1       tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ
  mathMode=3       n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  mathMode=3       n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  mathMode=3       tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ

$ java MtlMps.java
MPSMatrixMultiplication class = true

f32 n x n gemm, ms per call
n       ours resident   MPS resident    ours + copy     MPS + copy
128          0.156 ms       0.192 ms       0.165 ms       0.199 ms   (MPS 0.8x ours; agree to 0.0)
256          0.236 ms       0.213 ms       0.264 ms       0.208 ms   (MPS 1.1x ours; agree to 0.0)
512          0.741 ms       0.287 ms       0.304 ms       0.228 ms   (MPS 2.6x ours; agree to 0.0)
1024         1.129 ms       0.335 ms       1.387 ms       0.586 ms   (MPS 3.4x ours; agree to 0.0)
2048         9.363 ms       1.717 ms      10.179 ms       2.486 ms   (MPS 5.5x ours; agree to 0.0)

$ java MtlMpsDiff.java
n=256   differing cells 0/65536 | ours vs oracle 6.31e-07 | MPS vs oracle 6.31e-07 | ours vs MPS 0.00
n=1024  differing cells 0/1048576 | ours vs oracle 1.28e-06 | MPS vs oracle 1.28e-06 | ours vs MPS 0.00

$ java AccelerateProbe.java
Accelerate cblas, ms per n x n gemm (single thread of control, library may thread):
n         dgemm f64    sgemm f32 java f64 loop
64         0.004 ms     0.002 ms      60.7 ms   (146 / 242 GFLOP/s)
128        0.012 ms     0.005 ms       1.7 ms   (350 / 839 GFLOP/s)
256        0.073 ms     0.025 ms      10.6 ms   (458 / 1340 GFLOP/s)
512        0.331 ms     0.096 ms     111.0 ms   (811 / 2795 GFLOP/s)
1024       2.833 ms     0.819 ms     962.4 ms   (758 / 2623 GFLOP/s)
2048      21.852 ms     5.446 ms       NaN ms   (786 / 3155 GFLOP/s)

$ java MtlCompileCost.java   # three consecutive runs
MTLCreateSystemDefaultDevice   13.9 ms | newLibraryWithSource    2.6 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
MTLCreateSystemDefaultDevice   12.2 ms | newLibraryWithSource    2.7 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
MTLCreateSystemDefaultDevice   14.7 ms | newLibraryWithSource    2.9 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
```

### The bfloat16 GEMV on the GB10 (2026-09-06, todo-490)

Base `628c4048` plus the item's own tree, GraalVM 25 (Oracle), driver 580 / CUDA 13, load average
under 1.0 for every table below unless stated. The kernel probe first, because it decided the
shipped kernels: every load width lands on the same ~138 GB/s, the compensated float-float pair
(`_ff`) lands on the device's bandwidth AND on the double-accumulated oracle's bits on every row,
and the plain float sum (`_f`) is as fast and off on most rows. (The small shapes' 400-1100 GB/s
are L2-resident re-reads; the two head shapes are the DRAM truth.)

```
$ cd .todo/artefacts/123-gpu-acceleration && nvcc -arch=compute_75 -ptx -fmad=false --extended-lambda gemv-bf16-probe.cu -o gemv-bf16-probe.ptx
$ java --enable-native-access=ALL-UNNAMED -Xmx8g Bf16KernelProbe.java
NVIDIA GB10, 48 SMs, checked-in compute_75 PTX loaded in 15.84 ms

rows x cols   bf16 MB |       bf16_x1       bf16_x2       bf16_x4       bf16_x8       bf16_ff        bf16_f |        f32_x1        f32_x4        f32_ff   (us/call, GB/s)
1024x1024         2.1 |   24.3     86   23.4     90   23.3     90   22.9     92    8.0    263    8.1    259 |   22.6    186   23.1    182    9.1    463   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 807, f32 x1 0 ff 0
3584x1024         7.3 |   61.6    119   62.1    118   62.4    118   61.9    119   12.1    605   10.2    717 |   61.9    237   61.5    239   12.5   1175   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 2814, f32 x1 0 ff 0
6144x1024        12.6 |   95.2    132   95.6    132   94.9    133   95.3    132   15.0    837   13.2    950 |   96.7    260   96.4    261   54.3    464   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 4786, f32 x1 0 ff 0
2048x5632        23.1 |  181.6    127  182.7    126  183.7    126  183.4    126   38.4    601   37.6    614 |  222.9    207  207.3    223  201.9    229   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 1974, f32 x1 0 ff 0
4096x4096        33.6 |  242.0    139  243.5    138  246.2    136  243.3    138  134.3    250  140.4    239 |  304.4    220  297.3    226  288.6    232   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 3697, f32 x1 0 ff 0
32000x2048      131.1 |  926.4    141  926.5    141  932.0    141  924.6    142  565.0    232  584.1    224 | 1149.3    228 1128.5    232 1118.9    234   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 27387, f32 x1 0 ff 0
248320x1024     508.6 | 3674.1    138 3670.6    139 3674.1    138 3654.3    139 2170.5    234 2217.1    229 | 4376.1    232 4259.2    239 4259.6    239   x1==f32x1 0, x4==f32x4 0, ff==f32ff 0 | vs oracle: x1 0 x4 0 ff 0 f 193622, f32 x1 0 ff 0
```

The shipped route on the compensated kernels (x up, launch, y down; the matrix resident from its
second sight), against the CPU's fused bf16 kernel on one thread:

```
$ java --enable-native-access=ALL-UNNAMED -Xmx8g -cp target/classes .todo/artefacts/123-gpu-acceleration/Bf16MatvecCrossover.java
device: NVIDIA GB10 (sm_121, 48 SMs, driver API 13.0)

rows x cols      elems  bf16 MB |  bf16 min  bf16 med |   f32 min   f32 med |  cold min  cold med | mismatch
256x256          65536      0.1 |       NaN       NaN |       NaN       NaN |       NaN       NaN |       0
288x288          82944      0.2 |       NaN       NaN |       NaN       NaN |       NaN       NaN |       0
384x384         147456      0.3 |      10.0      11.4 |      10.8      11.3 |      22.3      24.1 |       0
512x512         262144      0.5 |      10.7      11.3 |      10.6      12.1 |      26.3      27.4 |       0
768x288         221184      0.4 |       9.1      10.9 |      10.0      10.4 |      24.3      25.4 |       0
288x768         221184      0.4 |       9.5      11.1 |       8.7      11.2 |      23.8      25.3 |       0
768x768         589824      1.2 |       9.8      11.2 |       9.0      11.1 |      36.5      36.9 |       0
1024x1024      1048576      2.1 |       9.3      11.2 |      10.6      12.3 |      51.6      55.0 |       0
2048x1024      2097152      4.2 |      12.1      20.5 |      14.4      20.5 |      98.1      99.0 |       0
1024x2048      2097152      4.2 |      11.3      13.2 |      14.3      20.4 |      97.6      98.8 |       0
3584x1024      3670016      7.3 |      15.4      20.6 |      20.0      20.5 |     153.0     153.9 |       0
1024x3584      3670016      7.3 |      19.4      20.5 |      19.2      20.4 |     151.4     160.0 |       0
6144x1024      6291456     12.6 |      20.4      21.6 |      58.2      63.7 |     264.3     288.2 |       0
5632x2048     11534336     23.1 |      45.8      49.1 |     198.3     205.0 |     480.3     487.1 |       0
2048x5632     11534336     23.1 |      48.3      51.1 |     206.1     211.1 |     455.6     457.0 |       0
2048x2048      4194304      8.4 |      16.2      20.5 |      19.1      20.5 |     170.9     178.6 |       0
4096x4096     16777216     33.6 |     124.8     128.9 |     268.3     274.5 |     698.6     701.9 |       0
32000x288      9216000     18.4 |      37.8      39.9 |     162.5     169.8 |     389.4     395.7 |       0
32000x2048    65536000    131.1 |     575.3     582.6 |    1103.2    1111.9 |    3607.1    4028.0 |       0
248320x1024  254279680    508.6 |    2216.0    2228.3 |    4280.9    4290.3 |   10855.0   10873.1 |       0
```

```
$ java -jar $JAR matvec-bf16-baseline.lisp -o MvB.class --simd && java --add-modules jdk.incubator.vector -Xmx8g MvB   # Graal
256 x 256 BFLOAT16: 6.5 us/call
256 x 256 SINGLE-FLOAT: 5.0 us/call
288 x 288 BFLOAT16: 8.0 us/call
288 x 288 SINGLE-FLOAT: 6.0 us/call
384 x 384 BFLOAT16: 14.0 us/call
384 x 384 SINGLE-FLOAT: 10.5 us/call
512 x 512 BFLOAT16: 25.0 us/call
512 x 512 SINGLE-FLOAT: 15.0 us/call
768 x 288 BFLOAT16: 20.0 us/call
768 x 288 SINGLE-FLOAT: 15.0 us/call
288 x 768 BFLOAT16: 20.0 us/call
288 x 768 SINGLE-FLOAT: 10.0 us/call
768 x 768 BFLOAT16: 50.0 us/call
768 x 768 SINGLE-FLOAT: 35.0 us/call
1024 x 1024 BFLOAT16: 66.7 us/call
1024 x 1024 SINGLE-FLOAT: 66.7 us/call
2048 x 1024 BFLOAT16: 166.7 us/call
2048 x 1024 SINGLE-FLOAT: 133.3 us/call
1024 x 2048 BFLOAT16: 166.7 us/call
1024 x 2048 SINGLE-FLOAT: 133.3 us/call
3584 x 1024 BFLOAT16: 300.0 us/call
3584 x 1024 SINGLE-FLOAT: 300.0 us/call
1024 x 3584 BFLOAT16: 300.0 us/call
1024 x 3584 SINGLE-FLOAT: 300.0 us/call
6144 x 1024 BFLOAT16: 566.7 us/call
6144 x 1024 SINGLE-FLOAT: 533.3 us/call
5632 x 2048 BFLOAT16: 1033.3 us/call
5632 x 2048 SINGLE-FLOAT: 1133.3 us/call
2048 x 5632 BFLOAT16: 1000.0 us/call
2048 x 5632 SINGLE-FLOAT: 1233.3 us/call
2048 x 2048 BFLOAT16: 366.7 us/call
2048 x 2048 SINGLE-FLOAT: 300.0 us/call
4096 x 4096 BFLOAT16: 1466.7 us/call
4096 x 4096 SINGLE-FLOAT: 1800.0 us/call
32000 x 288 BFLOAT16: 933.3 us/call
32000 x 288 SINGLE-FLOAT: 966.7 us/call
32000 x 2048 BFLOAT16: 5900.0 us/call
32000 x 2048 SINGLE-FLOAT: 7500.0 us/call
248320 x 1024 BFLOAT16: 22600.0 us/call
248320 x 1024 SINGLE-FLOAT: 29800.0 us/call

$ java --add-modules jdk.incubator.vector -Xmx8g -XX:-UseJVMCICompiler MvB   # C2
256 x 256 BFLOAT16: 5.0 us/call
256 x 256 SINGLE-FLOAT: 4.0 us/call
288 x 288 BFLOAT16: 6.0 us/call
288 x 288 SINGLE-FLOAT: 5.5 us/call
384 x 384 BFLOAT16: 10.0 us/call
384 x 384 SINGLE-FLOAT: 9.0 us/call
512 x 512 BFLOAT16: 15.0 us/call
512 x 512 SINGLE-FLOAT: 15.0 us/call
768 x 288 BFLOAT16: 15.0 us/call
768 x 288 SINGLE-FLOAT: 10.0 us/call
288 x 768 BFLOAT16: 15.0 us/call
288 x 768 SINGLE-FLOAT: 10.0 us/call
768 x 768 BFLOAT16: 35.0 us/call
768 x 768 SINGLE-FLOAT: 35.0 us/call
1024 x 1024 BFLOAT16: 66.7 us/call
1024 x 1024 SINGLE-FLOAT: 66.7 us/call
2048 x 1024 BFLOAT16: 133.3 us/call
2048 x 1024 SINGLE-FLOAT: 100.0 us/call
1024 x 2048 BFLOAT16: 133.3 us/call
1024 x 2048 SINGLE-FLOAT: 100.0 us/call
3584 x 1024 BFLOAT16: 233.3 us/call
3584 x 1024 SINGLE-FLOAT: 300.0 us/call
1024 x 3584 BFLOAT16: 233.3 us/call
1024 x 3584 SINGLE-FLOAT: 266.7 us/call
6144 x 1024 BFLOAT16: 433.3 us/call
6144 x 1024 SINGLE-FLOAT: 500.0 us/call
5632 x 2048 BFLOAT16: 733.3 us/call
5632 x 2048 SINGLE-FLOAT: 1100.0 us/call
2048 x 5632 BFLOAT16: 733.3 us/call
2048 x 5632 SINGLE-FLOAT: 1166.7 us/call
2048 x 2048 BFLOAT16: 233.3 us/call
2048 x 2048 SINGLE-FLOAT: 233.3 us/call
4096 x 4096 BFLOAT16: 1100.0 us/call
4096 x 4096 SINGLE-FLOAT: 1833.3 us/call
32000 x 288 BFLOAT16: 700.0 us/call
32000 x 288 SINGLE-FLOAT: 900.0 us/call
32000 x 2048 BFLOAT16: 4400.0 us/call
32000 x 2048 SINGLE-FLOAT: 7400.0 us/call
248320 x 1024 BFLOAT16: 18000.0 us/call
248320 x 1024 SINGLE-FLOAT: 31600.0 us/call
```

Read against the threshold: the device is ~10 us at either width up to a million elements, so the
crossover is where the CPU passes 10 us -- bf16 at 384x384 (14 against 10.0, 1.4x) and clearly by
512x512 (25 against 10.7); f32 is a TIE at exactly 2^17 (10.5 against 10.8; the lane kernel has
four accumulators since `.todo/480`) and 1.5x at 768x288. The threshold stays at 2^17 for both
widths: moving it to 2^18 would drop llama2's 768x288 feed-forward matrices, a measured 1.5x. The
cold column never pays (22 us against the CPU's 14 at 384x384), so the two-sight rule stands too.

### Where a Qwen3.5-0.8B forward pass goes under the flag (2026-09-06, todo-718)

Develop `17525dbf` (the day `490` closed), GraalVM 25.0.4, JVM class output compiled `--gpu --simd
--parallel` and `--simd --parallel`, `-Xmx16g -m chat -t 0 -w bf16` on the cat prompt from the BF16
GGUF (21 prompt ids, so `-n 64` is 85 forward passes), load average under 1.5 throughout. The item
asked where "an 86 ms token" goes; the first answer is that 86 ms was the printed `tok/s` -- 64
sampled tokens over 84 forwards including the warm-up -- and the forward itself, by the difference
of a 128- and a 64-token run (two of each), is:

```
--simd --parallel, 16 threads      printed 19.1 19.5 (n 64)  27.1 25.5 (n 128)   -> 24 ms a forward (22-27)
--gpu --simd --parallel, 16        printed 11.4 10.6          14.5 14.7           -> 46 ms (42-50)
--simd, one thread                 printed  7.7  7.6           9.3  9.6           -> 81 ms (77-85)
--gpu --simd, one thread           printed 10.4 11.3          14.8 14.3           -> 45 ms (39-51)
```

`decode-per-token.py` over `nsys profile -t cuda -s none` of the 16-thread device arm (63 head
launches; the profiler adds ~10 ms a forward, the SHAPE is the result):

```
forward period ms: median 56.37  min 53.39  max 93.41  (n=58)     first ten: 93 86 78 77 93 77 81 77 79 74
kernel time/forward ms: 7.51  launches 229
    gemv_bf16  grid  31040:   2.16 ms  x1      the 248320x1024 head
    gemv_bf16  grid    448:   1.60 ms  x48     w1 / w3, 3584x1024
    gemv_bf16  grid    128:   1.31 ms  x48     w2 / wo, 1024-row
    gemv_bf16  grid    768:   1.02 ms  x18     wqkv, 6144x1024
    gemv_bf16  grid    256:   0.59 ms  x30     wz, wq, the attention gate
    gemv_f32   grid     32:   0.42 ms  x36     the value cache, 256x4096
    gemv_f32   grid    512:   0.31 ms  x36     the key cache, 4096x256
    gemv_bf16  grid     64:   0.09 ms  x12     wk / wv
memcpy HtoD/forward: count 193  MB 102.03  device ms 1.89      4194304 bytes: 24 a forward (the KV caches)
memcpy DtoH/forward: count 229  MB 3.22    device ms 0.21      993280: the logits, 0.7 a forward
CUDA API time on the calling thread, ms/forward (median), calls/forward:
    cuMemcpyDtoH_v2          7.89 ms  x229
    cuMemcpyHtoD_v2          2.43 ms  x193
    cuLaunchKernel           0.65 ms  x229
    cuMemAllocAsync          0.42 ms  x422
    cuMemGetInfo_v2          0.14 ms  x4
    cuMemFreeAsync           0.13 ms  x120
    cuCtxSynchronize         0.11 ms  x169
    TOTAL                   11.88 ms
```

36 KV launches a forward, not 48: each of the 12 caches is written every token and read by four
heads, so the two-sight rule runs decline (the CPU lane kernel), upload, hit, hit -- every token.

`decode-jfr-agg.py` over JFR at one thread (2 ms sampling; the decode window over 85 forwards, so
a share times 56 ms is ms a forward with the warm-up in):

```
--gpu --simd    2161 samples, 4.78 s                    --simd      3047 samples, 6.41 s
 40.4%  residency guards (materialize / written)         88.0%  simd kernels (matvecRowsBf16 70.0, matvecRowsF 11.8)
 26.1%  device wait (DtoH copy / driver call)            12.0%  lisp code
 20.0%  lisp code (typed loops, aref/aset, boxing)
 13.5%  simd kernels (matvecRowsF 6.3: the 12 first-sight KV GEMVs)
 35.0%  GATED-DELTA-RULE  [26.2 guards, 8.8 loop]        2.4%  GATED-DELTA-RULE
 15.1%  CAUSAL-CONV       [7.6 guards, 6.5 loop]         5.7%  CAUSAL-CONV
  3.8%  SILU-IN-PLACE     [3.1 guards, 0.7 loop]         0.9%  SILU-IN-PLACE
top frames: CudaGemm.materialize:1817 20.2%  memcpyDtoHPinned 10.4%  GATED-DELTA-RULE 8.8%  Gpu.written:599 6.8%
callers of the guards: GATED-DELTA-RULE -> _gpuWritten (the TYPED loop, per store; CudaGemm.written
materializes first), CAUSAL-CONV -> _fvAref2 -> _gpuMaterialize and _fvAset1 -> _gpuWritten (boxed on
both arms), SILU-IN-PLACE -> _ivAset1 (boxed on both arms)
```

So the same three Lisp functions are 6.8 ms a forward without the flag and ~30 with it; the device
kernels are 7.5 of the 45; and a Q4 GEMV could shrink only the 6.8 ms of bf16 kernel time in that
7.5 -- `.kb/gpu.md`, "What is deliberately NOT here". Follow-ups: `.todo/723` (the guards),
`.todo/724` (the printed rate), `.todo/725` (the full-length cache -- closed 2026-09-06: the cache
now grows with the position reached, the 100 MB a forward stopped going up and the arm went
25.0 -> 18.5 ms; `.kb/gpu.md`), `.todo/726` (the Q4 refusal re-taken on the measurement below).

### The Q4 ceiling, measured (2026-09-07, todo-726)

`.todo/718` refused a Q4 weight width on the device from a byte ratio -- a Q4_0 matrix is 0.28 of
a bf16 one, the bf16 GEMV was 6.8 ms of a 45 ms forward -- and wrote down that the share decays
when the denominator does. It did: `723` and `725` took the forward to 18.5 ms with the kernels
unmoved. `726` re-took it with two measurements on develop `01312e40` (the day's build; GraalVM
25.0.4, JVM class output `--gpu --simd`, one thread, `-Xmx16g -m chat -t 0 -i` the cat prompt from
the BF16 GGUF, load average under 1 before each run).

**The kernels** (`Q4KernelProbe.java`, the full output in the header's command): device-side time
of one launch, median and min over a rotation through >= 256 MB of copies ("cold") and over one
copy ("hot", what a microbenchmark sees -- 13.5 us for the 12.6 MB `wqkv` from L2 against 60 from
DRAM), every kernel within 2e-8 of its own double oracle relative to the row's sum of |terms|:

```
shape         x/fwd   bf16 cold med/min  in situ (718)   q8_0 best cold      q4_0 best cold        q4_0 GB/s
248320x1024     1     2204 / 2184 us       2160 us       1214 (l2) 223 GB/s   664 (dp2) 215 GB/s
6144x1024      18       60 /   57 us         57 us         35 (l2) 191         21.3 (dp2) 166
3584x1024      48       40 /   36 us         33 us       21.7 (dp4) 179       13.5 (dp2) 153
1024x3584      24       40 /   37 us         --          22.8 (dp4) 171       13.6 (dp2) 152
1024x2048      24       26 /   23 us         --          13.4 (dp4) 167        8.4 (dp2) 140
2048x1024      30       24 /   20 us         20 us       13.5 (l4)  165        8.4 (dp2) 141
512x1024       12        9 /    4 us        7.5 us        6.1 (dp4)  91        4.4 (dp4)  67

GEMV ms a forward (157 launches), cold median / cold min:
f32 13.85 / 12.34    bf16 7.60 / 7.01    q8_0 4.37 / 3.50 (dp4)    q4_0 2.54 / 1.71 (dp2)
```

Three readings. (1) The cold method is the decode step's: the in-situ `nsys` durations of `718`
fall between the probe's cold median and cold min at every shape (the rotation across 22-256
distinct buffers pays a TLB the model's own pages, hot every token, do not). (2) **The f32-x
kernels are not bandwidth-bound and the reason is the VECTOR, not the matrix**: a lane owning a
block reads its 32 x's at a 128-byte stride from its neighbours -- 32 L1 transactions a load --
so `q4_0_l1` and the `_split` layout (one 128-bit load a block, the best any layout can do on the
weight side) sit at 60-68 GB/s while `l4` reaches 207 at the head; the `_dp` shape, x quantized
to int8 per block as the CPU's Q8_0 contract already has it, reads 32 bytes a block as words the
lanes of a block share and is the fastest at every shape -- **0.24-0.33 of bf16 for Q4_0 (bytes
0.28), 0.50-0.58 for Q8_0 (bytes 0.53)**. (3) The floor shows only at 512 rows (64 blocks on 48
SMs); at the layer shapes Q4_0 is at 140-170 GB/s, the head at 215.

**The forward** (`slope.sh`: `LLAMA2_TRACE` counted the tokens, 64 and 256 generated every run;
forward = (256 / rate256 - 64 / rate64) / 192):

```
                 n=64 tok/s    n=256 tok/s    ms a forward
-w bf16  round 1   34.38         50.16          16.89
         round 2   33.90         50.50          16.57
-w f32   round 1   24.06         37.18          22.01
         round 2   24.72         35.89          23.67
```

Twice the GEMV bytes cost the forward 5.1-7.1 ms; the kernel difference in the probe is 6.25
cold-median, 5.5 scaled to the in-situ 6.74. **The arm is linear in the kernel time, one for one**
-- every launch is on the critical path (the next host form reads its result) and nothing else in
the forward moves with the width. So the projections are subtractions from 16.7 ms: Q4_0 saves
6.74 x (1 - 0.24..0.33) = **4.5-5.1 ms -> 12.2 ms, 1.37-1.44x**; Q8_0 saves **2.7-3.4 -> 13.5 ms,
1.20-1.25x**; and Q4_0 over a shipped Q8_0 is the difference, **1.4-1.8 ms, ~12%**.

**The decision** (`.kb/gpu.md`, "No Q4_0 / Q4_K weight width"): the ceiling is real, and the
refusal is now about ORDER. Q8_0 on the device (`.todo/728`) costs the device seam alone -- the
type, reader, quantizer, oracle and CPU kernel exist, ggml-org ships the file, and today `--gpu`
over it declines every GEMV to the CPU -- and takes 60% of the Q4_0 gain. Q4_0 stays refused
behind it: its increment over Q8_0 is 1.4-1.8 ms a forward at the 8.5%-error width against the
0.75% one, for the format's CPU arms on every backend. Re-take after `728`, against that
increment; it flips on a model whose bf16 GEMV is the majority of its forward (this one's is 40%),
or on a discrete card. ggml-org's Qwen3.5-0.8B set is BF16 / Q8_0 / plain Q4_0 -- no K-quants --
which is one reader fewer for this model and none fewer for the width.

### The exact Q8_0 kernel, measured (2026-09-07, todo-728)

`726` measured the Q8_0 ceiling with `gemv_q8_0_dp4`, a warp-per-row fold over `__dp4a` -- and the
width's CPU contract forbids that fold: the defun and the `--simd` kernel are ONE value, with four
f32 lane accumulators walked over the blocks in order, and a device kernel that joined the f32 row's
tolerance instead would have put the first `--gpu` exception into `.kb/quantized-matrix.md`'s
invariant and cost llm's story its byte-identity under the flag. So the question `728` had to settle
first was whether a kernel that keeps the contract's four sequential chains a row still streams. It
does, at 0.87 of the free fold's rate. `gemv-q8-exact-probe.cu` / `Q8ExactKernelProbe.java`, GB10,
develop `23e90a21`, the same cold method and the same seven shapes as `726`, every candidate
checked raw-bit-equal on every row against the contract transcribed (`contract()` in the Java):

```
ms of GEMV a FORWARD (157 launches), cold median / cold min:
bf16 (shipped)               7.70 / 6.92
q8_0_dp4 (726's, NOT the bits) 4.63 / 3.84
q8_0_x4   4 threads a row, 4x4 transpose-reduce over shuffles     8.35 / 7.48   bit-identical
q8_0_x4r  x4, the scale product by every thread (fp64 per thread) 7.74 / 6.93   bit-identical
q8_0_b4   4 threads a row, one lane a thread, no integer shuffle  7.73 / 6.95   bit-identical
q8_0_x8   8 threads a row, 4x4 transpose-reduce                   5.47 / 4.93   bit-identical
q8_0_b8d  b8 with the lane packed and one __dp4a                  5.36 / 4.68   bit-identical
q8_0_b8   8 threads a row, one lane a thread, one xor-4 shuffle   5.32 / 4.66   bit-identical  <- shipped
quantize  a launch of its own, hot, x 157                         1.04 / 0.84   (+4.2 us back to back with the GEMV)

per shape, b8 cold median (us) / GB/s, against bf16 and dp4:
248320x1024  1417 / 191   bf16 2207   dp4 1258
6144x1024      44 / 153        60        38
3584x1024      26 / 150        40        24
1024x3584      31 / 127        41        25
1024x2048      20 / 109        26        16
2048x1024      17 / 128        26        16
512x1024        9 /  59        10         8
```

Three readings. (1) **Four threads a row cannot stream** at any shape (x4, x4r, b4 all sit at bf16's
time with half the bytes): the per-thread chain is 8-16 loads deep before the next block's can issue,
and 4 x rows threads is a quarter of what the head needs to hide DRAM latency; eight a row is the
minimum, and it is the same x8 -> b8 difference of one lane a thread that removes the 4x4 transpose
(seven shuffles a block a thread) for the same bits. (2) **The instruction count is not the wall once
the loads are wide enough**: b8d packs the lane into a word and folds it with one `__dp4a` and is no
faster than b8's four multiplies, so what separates b8 from dp4 (0.87) is the byte loads at stride 4
against dp4's 16-bit words and the per-thread chain, not the ALU. (3) **The activation must not be a
launch**: a `quantize_q8_0` kernel is 5-7 us of event time alone and +4.2 us on the stream back to
back with the GEMV -- 0.7-1.1 ms a forward for 157 of them, plus the launch call on the host -- where
the contract's quantizer on the host is 0.72 / 1.42 / 2.47 us at 1024 / 2048 / 3584 columns under
Graal (1.7 / 3.5 / 6.0 under C2), ~0.16 ms a forward, and it REPLACES the f32 vector's upload rather
than adding to it (the packed buffer is a fifth of the vector's bytes). The scale product
`f32(f64(d) * sx)` is one fp64 multiply a block a row, computed by one thread of the eight and shuffled,
since GB10's fp64 rate is a ceiling the bf16 kernel already hit once (`490`).

**In situ** (`examples/llm/README.md`, "Q8_0 weights on the device"): 13.9 ms a forward over the
Q8_0 GGUF against 16.7 over the BF16 one, 1.20x -- `726`'s prediction for the width to the tenth --
with the 256 tokens byte-identical between the device arm and the CPU arm. Finding on the way: the
model's `split-gated-q` rebuilt Qwen3.5's `attn_q` halves as general arrays from a quantized source,
so the Q8_0 file's `wq` / `gate` GEMVs had been running the boxed defun on every arm (5.6 tok/s at
`--simd`); split by byte span now, 9.7.

### The GEMV on Metal (2026-08-22, todo-477), same machine

The CPU column first -- `matvec-baseline.lisp` under `--simd` on the JVM class output, M4
Max (the GB10's own column is in `.kb/gpu.md`'s GEMV section; this CPU is 1.5-2x faster at
every shape, which moves the crossover as much as the floor does):

```
$ java --add-modules jdk.incubator.vector Mv     # matvec-baseline.lisp, JVM --simd, M4 Max
64 x 64 SINGLE-FLOAT: 1.5 us/call          64 x 64 DOUBLE-FLOAT: 1.5 us/call
128 x 128 SINGLE-FLOAT: 1.5 us/call        128 x 128 DOUBLE-FLOAT: 2.5 us/call
192 x 192 SINGLE-FLOAT: 3.5 us/call        192 x 192 DOUBLE-FLOAT: 6.5 us/call
256 x 256 SINGLE-FLOAT: 5.5 us/call        256 x 256 DOUBLE-FLOAT: 12.5 us/call
288 x 288 SINGLE-FLOAT: 7.5 us/call        288 x 288 DOUBLE-FLOAT: 15.5 us/call
384 x 384 SINGLE-FLOAT: 14.5 us/call       384 x 384 DOUBLE-FLOAT: 28.5 us/call
512 x 512 SINGLE-FLOAT: 25.0 us/call       512 x 512 DOUBLE-FLOAT: 55.0 us/call
768 x 288 SINGLE-FLOAT: 15.0 us/call       768 x 288 DOUBLE-FLOAT: 40.0 us/call
288 x 768 SINGLE-FLOAT: 20.0 us/call       288 x 768 DOUBLE-FLOAT: 55.0 us/call
768 x 768 SINGLE-FLOAT: 60.0 us/call       768 x 768 DOUBLE-FLOAT: 130.0 us/call
1024 x 1024 SINGLE-FLOAT: 100.0 us/call    1024 x 1024 DOUBLE-FLOAT: 233.3 us/call
1448 x 1448 SINGLE-FLOAT: 233.3 us/call    1448 x 1448 DOUBLE-FLOAT: 500.0 us/call
1536 x 1536 SINGLE-FLOAT: 266.7 us/call    1536 x 1536 DOUBLE-FLOAT: 566.7 us/call
2048 x 2048 SINGLE-FLOAT: 500.0 us/call    2048 x 2048 DOUBLE-FLOAT: 1033.3 us/call
32000 x 288 SINGLE-FLOAT: 800.0 us/call    32000 x 288 DOUBLE-FLOAT: 1733.3 us/call
256 x 48 SINGLE-FLOAT: 4.0 us/call         256 x 48 DOUBLE-FLOAT: 4.0 us/call
48 x 256 SINGLE-FLOAT: 1.0 us/call         48 x 256 DOUBLE-FLOAT: 2.0 us/call
4096 x 4096 SINGLE-FLOAT: 2333.3 us/call   4096 x 4096 DOUBLE-FLOAT: 4333.3 us/call

$ java --enable-native-access=ALL-UNNAMED MtlMatvecCrossover.java
device: Apple M4 Max

rows x cols     elems |  comp cold comp resid  comp kern | acc32 resid acc32 kern
64x64            4096 |       79.7       85.5       87.8 |       78.9       77.4
128x128         16384 |       83.6       79.0       77.0 |       76.8       79.0
192x192         36864 |       79.8       75.7       78.7 |       78.9       78.3
256x256         65536 |       85.2       80.9       83.1 |       76.0       78.3
288x288         82944 |       86.6       78.2       76.1 |       80.5       78.7
384x384        147456 |       92.1       77.1       69.5 |       78.9       79.2
512x512        262144 |       99.7       77.7       80.0 |       79.5       80.6
768x288        221184 |       99.0       77.8       84.0 |       78.9       78.8
288x768        221184 |      103.9       88.5       78.6 |       83.3       76.4
768x768        589824 |      128.8       90.5       85.5 |       90.3       77.0
1024x1024     1048576 |      160.7       90.1       77.4 |       74.5       86.6
1448x1448     2096704 |      228.8       93.4       96.5 |       94.0       93.0
1536x1536     2359296 |      243.1       94.4       92.9 |       99.8       88.1
2048x2048     4194304 |      364.5      104.8      100.8 |      102.1      100.2
32000x288     9216000 |      753.0      185.2      176.7 |      184.4      181.2
256x48          12288 |       79.0       82.1       82.5 |       80.3       81.0
48x256          12288 |       78.9       82.0       77.1 |       78.9       77.8
4096x4096    16777216 |     1310.4      249.1      273.8 |      256.5      276.2

1024x768 f32 against the scalar defun's oracle (double acc, narrowed):
  compensated, contract off: 1024/1024 rows bit-identical, worst 0.00e+00 of the largest cell
  compensated, default:      1024/1024 rows bit-identical, worst 0.00e+00
  float accumulator:         229/1024 rows bit-identical, worst 2.87e-07
  --simd lanes:              144/1024 rows bit-identical, worst 5.73e-07
```

Read the three columns against the floor: "kern" IS the floor (~77 us a command buffer)
until the matrix is several megabytes, "resid" adds the x copy and the y copy and is within
noise of it, and "cold" adds a memcpy of the whole matrix -- 753 us at the head against the
CPU's 800 for the same bytes, so the cold trip can never pay here and the two-sight rule is
not a refinement but the member. The accumulator block is the reason the Metal GEMV lands
on the defun's bits without a `double`: the compensated float-float pair is bit-identical on
every row (with or without the contraction pragma), the plain float sum on 229.

```
$ java --enable-native-access=ALL-UNNAMED -cp target/classes .todo/artefacts/123-gpu-acceleration/MtlGemvInSitu.java
device: Apple M4 Max (Metal, unified memory, 107 GB working set)
-- the shipped route back to back, us per call (the first shape pays the clock ramp) --
    32000x288  round 0: min  299.2  median  381.8  mean  394.8  p90  428.2
    32000x288  round 1: min  251.3  median  366.8  mean  364.9  p90  437.1
    32000x288  round 2: min  193.1  median  227.7  mean  235.5  p90  264.5
    4096x4096  round 0: min  268.3  median  297.0  mean  298.2  p90  307.4
    4096x4096  round 1: min  277.4  median  291.0  mean  293.3  p90  295.7
    4096x4096  round 2: min  270.1  median  291.6  mean  293.0  p90  298.4
    1536x1536  round 0: min  113.9  median  126.7  mean  134.0  p90  146.8
    1536x1536  round 1: min  106.1  median  120.3  mean  122.0  p90  125.3
    1536x1536  round 2: min  105.9  median  118.3  mean  118.2  p90  119.7
    2048x2048  round 0: min  108.2  median  125.7  mean  126.6  p90  128.5
    2048x2048  round 1: min  111.4  median  124.5  mean  125.5  p90  126.5
    2048x2048  round 2: min  109.5  median  124.7  mean  125.3  p90  126.4
    1536x1536  round 0: min  103.8  median  115.5  mean  115.6  p90  118.9
    1536x1536  round 1: min  106.8  median  115.9  mean  116.7  p90  118.7
    1536x1536  round 2: min  105.6  median  117.0  mean  116.8  p90  118.8
    32000x288  round 0: min  160.9  median  184.1  mean  181.6  p90  194.6
    32000x288  round 1: min  157.8  median  164.2  mean  166.8  p90  170.6
    32000x288  round 2: min  156.8  median  163.7  mean  166.0  p90  165.9
-- the same resident call after a CPU gap, mean us per call --
    1536x1536  gap     0 us -> call  116.6 us
    1536x1536  gap   100 us -> call  108.7 us
    1536x1536  gap   500 us -> call  150.5 us
    1536x1536  gap  1000 us -> call  174.0 us
    1536x1536  gap  2500 us -> call  475.6 us
    1536x1536  gap  5000 us -> call  464.2 us
    1536x1536  gap 10000 us -> call  606.9 us
    32000x288  gap     0 us -> call  210.7 us
    32000x288  gap   100 us -> call  204.2 us
    32000x288  gap   500 us -> call  242.5 us
    32000x288  gap  1000 us -> call  351.9 us
    32000x288  gap  2500 us -> call  807.2 us
    32000x288  gap  5000 us -> call  825.3 us
    32000x288  gap 10000 us -> call  982.3 us
```

Two things to read out of it. The shipped route is the probe's best-of plus its own
bookkeeping and the clock's spread -- medians of ~115 us at 1536x1536, ~125 at 2048x2048,
~165-205 at the head -- and the FIRST shape a process measures pays the ramp (the head:
382 -> 367 -> 228 across three rounds, and 164 once warm). And the gap table is the ceiling
on a decode loop: the same resident call costs ~2.5x more after 2.5 ms of CPU quiet, because
the GPU lowers its clocks after about a millisecond idle, which is why `examples/llama2`
(one head GEMV per 2.7 ms token) is 1.0x on this machine with the flag.

### The five lines that matter

1. **`'double' is not supported in Metal`** -- from the compiler, not from a benchmark.
   CUDA's fp64 is 44x slower than its fp32 but it exists; MSL has no double at all, and
   `half` / `bfloat` / 64-bit int all compile fine, so this is a deliberate omission in the
   language. `linalg`'s default element type is double-float, so on Apple a `--gpu` is not
   "f32 is where the win is" -- it is "f32 or nothing".

2. **`newLibraryWithSource` costs 2.3 ms warm and needs no toolchain.** There is no Xcode
   on this machine (`xcrun metal` is absent) and MSL still compiled at run time, because
   the compiler lives in the OS. That is strictly better than the PTX story -- no
   build-time artifact to generate, check in or version against a virtual architecture.
   32 ms on the first ever run, ~2.5 ms afterwards (the OS caches across processes, like
   `~/.nv/ComputeCache`), and 0.1 ms for the same source a second time in-process. The
   real startup cost is `MTLCreateSystemDefaultDevice` at 12-15 ms, which is what the
   availability probe would pay.

3. **The per-call floor is ~85 us, five times CUDA's 16-18 us**, and it is flat from 8x8
   to 128x128 exactly as CUDA's was. Spinning on `[cb status]` instead of blocking in
   `waitUntilCompleted` changes nothing (83.1 vs 81.1 us), so this is the round trip
   itself, not the blocking primitive -- there is no cheaper wait to find. But the cost is
   per COMMAND BUFFER, not per dispatch: 50 dispatches in one command buffer cost 8.8 us
   each. Batching is worth 10x, and it is the same mechanism residency needs.

4. **MPS is 5.5x the naive kernel at n=2048, and bit-identical to it.** Zero differing
   cells out of 1,048,576 at n=1024, both landing 1.28e-6 from the f64 oracle -- verified
   with a poisoned output buffer so this is agreement, not a silent no-op. Unlike cuBLAS
   it ships in the OS, so it costs no dependency and no toolkit. Both halves of the cuBLAS
   verdict invert here.

5. **Accelerate's CPU BLAS beats both of them below n~1024, has a double, and has no 85 us
   floor.** 800 GFLOP/s at f64 -- nearly twice the GB10's cuBLAS DGEMM (420) -- and 3200
   GFLOP/s at f32, which is faster than our Metal kernel at every size measured. It is
   plain C, in the OS, reachable in four lines of FFM. Still 35-121x `--simd` AFTER
   todo-469 gave the f32 kernel its lanes, so that landing does not dent it. See
   `../../../.kb/gpu.md` for what it does to the plan, and `../../../.kb/linalg-blas.md` for the
   item it became.

### The width probe, same machine

`.kb/linalg-simd.md` cites `MatmulFProbe` and warns that it answers differently per
architecture. It does, and the two aarch64 machines are not interchangeable either:

```
$ java --add-modules jdk.incubator.vector MatmulFProbe.java     # Apple M4 Max
f32 lanes=4, f64 lanes=2
      laneF32 vs oracle: max 701460 ulp, max rel 0.0428
n=256  scalarAcc   4.82 ms | laneF2D  557.52 ms (bit-identical) | laneF32   1.26 ms (DIFFERS) | f64   2.34 ms
      laneF32 vs oracle: max 440342 ulp, max rel 0.0310
n=512  scalarAcc  35.86 ms | laneF2D 4473.65 ms (bit-identical) | laneF32   9.71 ms (DIFFERS) | f64  18.07 ms
```

Same ranking as the GB10 run recorded above -- `laneF32` wins, `laneF2D` is catastrophic,
and the relative error against the oracle is identical to five digits because that is a
property of the arithmetic and not of the machine -- but the magnitudes differ. Re-running
the probe on the GB10 to check that the `.kb` row really was that machine's confirms it:
`n=512  scalarAcc 38.97 | laneF2D 7325.79 | laneF32 10.53 | f64 19.98`, against the M4's
35.86 / 4473.65 / 9.71 / 18.07. The two aarch64 machines differ by 1.6x on `laneF2D`, so
the row that was labelled "M4" was the GB10's; the `.kb` table now carries both, named.

### The same probe on the GB10, which is why it now prints a verdict

`AccelerateProbe` walks a candidate list so it runs on both machines. On the DGX Spark it
found something -- and that turned out to be the trap:

```
bound CBLAS: libblas.so.3
identifies as: no tuned-implementation marker found -- possibly the netlib reference; read the verdict
cblas, ms per n x n gemm (single thread of control, library may thread):
n         dgemm f64    sgemm f32 java f64 loop
64         0.070 ms     0.071 ms      79.7 ms   (7 / 7 GFLOP/s)
128        0.621 ms     0.541 ms       1.3 ms   (7 / 8 GFLOP/s)
256        4.517 ms     4.786 ms      11.1 ms   (7 / 7 GFLOP/s)
512       35.285 ms    36.125 ms     142.1 ms   (8 / 7 GFLOP/s)
1024     283.099 ms   354.779 ms    1236.5 ms   (8 / 6 GFLOP/s)
2048    2478.494 ms  2506.586 ms       NaN ms   (7 / 7 GFLOP/s)
```

7-8 GFLOP/s against Accelerate's 800, flat across every size, and f32 no faster than f64:
that is the **netlib reference implementation**, which is a specification written in
Fortran and not a tuned kernel. Confirmed rather than inferred -- `dpkg -S` answers
`libblas3:arm64`, Debian's reference package, and `update-alternatives` shows it as the
only provider at priority 10. Read it against the same machine's `--simd` column below
-- n=256 2.800 ms and n=512 21.200 ms -- and the result is that **the BLAS is 1.6x SLOWER
than the kernel rontolisp already has**. It beats only the naive triple loop.

Three things follow, and they are why the probe gained an `identify()` and a verdict line:

- **Finding a CBLAS is not finding a tuned BLAS**, and the earlier framing ("Linux ships
  none with the base system but very often carries one anyway") was too generous: carrying
  one very often means carrying the reference.
- **The soname cannot tell them apart.** Debian's `libblas.so.3` is an `update-alternatives`
  symlink that points at OpenBLAS when one is installed and at the reference when not, so
  a name-ordered candidate list is not a safety mechanism. The probe now looks for the
  marker symbols tuned implementations export (`openblas_get_config`, `mkl_get_version`,
  `bli_info_get_version_str`, ...) and measures anyway.
- **A `--blas` that bound whatever it found would be a silent 1.6x regression** on this
  machine, at `linalg`'s default width. That is a much worse failure than declining.

This is where an earlier draft of this file went wrong, and the mistake is worth keeping
visible because it is easy to make. It argued that installing OpenBLAS and re-measuring
was "the wrong experiment", on the grounds that the spike's founding rule -- **the runtime
requirement is what the OS or the driver already provides, and nothing the user installs**
-- forbids requiring such a machine. The rule is real and still governs what may be
REQUIRED. It says nothing about what may be MEASURED. Refusing to measure the
tuned-BLAS-on-Linux case meant leaving the size of the opportunistic tier unknown while
simultaneously complaining that it had no representative machine.

So it was measured.

### With a tuned BLAS installed: what the opportunistic tier is actually worth

`libopenblas0-pthread` 0.3.26 installed on the Spark (see the machine-changes log at the
end of this file). The probe tries `libopenblas.so.0` before `libblas.so.3`, so it binds
OpenBLAS directly regardless of what `update-alternatives` points at:

```
=== all threads (nproc=20) ===
bound CBLAS: libopenblas.so.0
identifies as: OpenBLAS (exports openblas_get_config)
n         dgemm f64    sgemm f32
64         0.010 ms     0.006 ms   (52 / 89 GFLOP/s)
128        0.028 ms     0.017 ms   (148 / 247 GFLOP/s)
256        0.245 ms     0.134 ms   (137 / 250 GFLOP/s)
512        1.073 ms     0.514 ms   (250 / 522 GFLOP/s)
1024       5.942 ms     2.943 ms   (361 / 730 GFLOP/s)
2048      43.921 ms    21.492 ms   (391 / 799 GFLOP/s)
verdict: 361 GFLOP/s f64 at n=1024 -- TUNED.

=== OPENBLAS_NUM_THREADS=1 ===
64         0.015 ms     0.009 ms   (34 / 61 GFLOP/s)
128        0.068 ms     0.034 ms   (62 / 122 GFLOP/s)
256        0.523 ms     0.258 ms   (64 / 130 GFLOP/s)
512        4.137 ms     2.030 ms   (65 / 132 GFLOP/s)
1024      33.002 ms    16.227 ms   (65 / 132 GFLOP/s)
2048     265.831 ms   128.435 ms   (65 / 134 GFLOP/s)
verdict: 65 GFLOP/s f64 at n=1024 -- TUNED.
```

Three readings, and the third is the uncomfortable one.

- **Against `--simd` on the same machine** (n=512: f64 21.4 ms, f32 10.9 ms), OpenBLAS is
  **20x threaded / 5.2x on one thread** at f64, and 21x / 5.4x at f32. So the
  opportunistic tier is worth having -- this is not the marginal case that would have let
  the BLAS item stay macOS-shaped. But note which number is which: 20x is 20 CORES, and
  rontolisp is single-threaded today, so binding a threaded BLAS silently makes
  `linalg:matmul` multi-threaded. 5.2x is the honest no-surprises figure and 20x is the
  one that comes with a semantic change.
- **Against Accelerate**, Grace's 391 GFLOP/s f64 across 20 cores is half the M4 Max's
  800. Apple's advantage is not merely that the library is guaranteed present.
- **Against the GPU on the same box, at f64, it is a TIE.** `--gpu` with copies does
  n=512 in 0.906 ms, n=1024 in 5.720, n=2048 in 40.541; threaded OpenBLAS does 1.073,
  5.942, 43.921. Within 20%, 4% and 8%. The GB10's fp64 is weak enough (420 GFLOP/s even
  from cuBLAS) that 20 Grace cores catch it. At f32 the GPU keeps a real but modest
  phase-1 lead: 1.6x at n=512, 2.3x at n=2048.

That last point belongs to todo-123 rather than to the BLAS item, and it sharpens what
`--gpu` is for on THIS class of machine: not f64 -- where a CPU BLAS the user may already
have is level with it -- but f32, residency, and the batched rank-3 shape. It is the same
conclusion Apple reached by a different route (there, MSL simply has no double), and it is
further evidence for phase 0.

The opportunistic tier now has one measured member on each side. Note that cuBLAS is NOT
part of the driver (here it comes from `libcublas-13-0`, pulled by `cuda-libraries-13-0`),
but it IS preinstalled and on the `ldconfig` path on any machine with the CUDA stack, so
its marginal cost on such a box is zero -- exactly like OpenBLAS on a machine that already
has numpy. If that tier is ever built it should be built once for both CPU and GPU, not
twice.

### The CPU baseline, same machine

Measured twice, because todo-469 landed between the two runs and it moves the f32 column
the GPU is compared against.

```
$ java --add-modules jdk.incubator.vector Mm2     # matmul-baseline.lisp, JVM --simd
                            BEFORE todo-469          AFTER todo-469 (5a3e8f16)
n=32   f64 / f32            0.100 / 0.050            0.050 / 0.100
n=64   f64 / f32            0.150 / 0.150            0.150 / 0.050
n=128  f64 / f32            0.600 / 0.700            0.550 / 0.300
n=256  f64 / f32            2.900 / 5.650            2.600 / 1.450
n=512  f64 / f32           25.100 / 41.600          22.100 / 11.350

$ java --add-modules jdk.incubator.vector W       # width-baseline.lisp, JVM --simd
                            BEFORE                   AFTER
matmul n=256  f64 / f32     5.100 / 24.650           2.500 / 1.500
matmul n=512  f64 / f32    68.300 / 86.850          20.050 / 11.350
add   1d      f64 / f32     0.540 / 0.300            0.300 / 0.200
dot   1d      f64 / f32     0.220 / 0.280            0.180 / 0.060
exp   1d      f64 / f32     7.660 / 2.180            1.360 / 1.240
sum   1d      f64 / f32     0.100 / 0.140            0.140 / 0.140
```

`matmul-baseline` is the honest CPU number and the one the todo quotes; `width-baseline`
drives its matmuls through a `funcall`ed lambda with a shorter warm-up, which is why its
BEFORE numbers are so much higher (its AFTER numbers agree with `matmul-baseline`, which
is itself worth noticing -- the old kernel was the thing that made the lambda path look
pathological). The `#f` anomaly the BEFORE column shows was not GB10-specific and was in
fact worse here -- 4.8x slower than `#d` at n=256 against 2x there -- and todo-469 has
since inverted it on this machine too: `#f` is now about 2x FASTER than `#d`. Every
Apple-side comparison below quotes the AFTER column.

## What is deliberately missing

- **No cross-platform probe.** The CUDA files need an NVIDIA driver, the Metal files need
  macOS, and neither set is guarded -- running the wrong half fails at the first
  `libraryLookup`. A real `am.ik.gpu` needs one availability probe that answers "no
  device" without throwing on either platform.
- **No Metal batched MPS.** `MPSMatrixMultiplication` has a batched descriptor
  (`matrixDescriptorWithRows:columns:matrices:rowBytes:matrixBytes:dataType:`) that would
  be the tuned counterpart to `gemm3_f32`; only the naive batched kernel was measured.
- **No Metal object lifetime worth the name.** `Mtl.release` exists and is called on
  buffers; every other `new*`/`alloc` result -- libraries, pipelines, queues, MPS objects,
  the `NSString`s built for every selector argument -- leaks, and there is one
  `autoreleasePoolPush`/`Pop` around each `main`. Real code needs the ownership rules
  (`new`/`alloc`/`copy` are +1, everything else is autoreleased) applied deliberately.
- **No Metal error path.** A failed `newLibraryWithSource:` is turned into an exception
  with the compiler's diagnostics, which is right for a probe and wrong for `--gpu`, which
  must decline to the CPU instead of signalling. Nothing checks
  `[commandBuffer error]` at all.
- **No error handling worth the name.** `Cu.check` exists; most call sites ignore the
  status. Real code needs a `CUresult` table (`silicon-cuda`'s `CUResult.java` is 685
  lines of exactly that) and a decline-on-error path, since `--gpu` must degrade to the
  CPU rather than signal.
- **No lifetime management.** Buffers are freed on the happy path only, and the primary
  context is retained and never released.
- **No `linalg` integration at all.** Nothing here touches a `LispFloatArray`, a decline
  sentinel or a call site. That is phase 1, and it is the point at which this directory
  stops being useful.

## Changes made to the spike machines

The probes here are read-only with one exception, recorded so the machines can be put
back and so a later reader knows which numbers came from a modified system.

### DGX Spark (`gx10-c90e`, GB10, Ubuntu 24.04 aarch64) -- 2026-08-20

**One package installed**, to measure what a tuned CPU BLAS is worth on Linux (the
"With a tuned BLAS installed" section above). Nothing else was changed; no dependency was
pulled and nothing was removed.

```bash
sudo apt-get install -y libopenblas0-pthread     # 0.3.26+ds-1ubuntu0.1
```

State before, as recorded by the probes themselves earlier that day:

```
libblas3:arm64        3.12.0-3build1.1     <- Debian's netlib reference, the ONLY provider
liblapack3:arm64      3.12.0-3build1.1
libopenblas0-pthread  not installed
update-alternatives:  libblas.so.3 -> /usr/lib/aarch64-linux-gnu/blas/libblas.so.3 (priority 10)
AccelerateProbe:      bound libblas.so.3, 7-8 GFLOP/s, no tuned marker
```

State after:

```
libopenblas0-pthread:arm64  0.3.26+ds-1ubuntu0.1
update-alternatives:  libblas.so.3 -> .../openblas-pthread/libblas.so.3 (priority 100, auto)
AccelerateProbe:      bound libopenblas.so.0, 361 GFLOP/s f64 threaded / 65 single
```

**The side effect is the alternatives switch**, not the package: `libblas.so.3` now
resolves to OpenBLAS for everything on the machine that links BLAS by that soname (numpy,
scipy, R, ...). That is normally an improvement -- they go from the reference
implementation to a tuned one -- but it IS a system-wide behaviour change and it was not
asked for by anything in this directory. The probe itself does not depend on it: its
candidate list tries `libopenblas.so.0` before `libblas.so.3`.

To revert completely:

```bash
sudo apt-get remove libopenblas0-pthread     # alternatives falls back to priority 10
update-alternatives --display libblas.so.3-aarch64-linux-gnu   # verify
```

To keep OpenBLAS installed but restore the reference as the system default:

```bash
sudo update-alternatives --set libblas.so.3-aarch64-linux-gnu \
     /usr/lib/aarch64-linux-gnu/blas/libblas.so.3
```

Two things that were NOT changed and are worth knowing: the CUDA stack was already
present (`libcublas-13-0` via `cuda-libraries-13-0`, on the `ldconfig` path) and no NVPL
is installed. `git pull` was run in `/home/maki/git/rontolisp` and the tree is otherwise
clean.

### Apple M4 Max (macOS 26.3.1) -- 2026-08-20

**Nothing installed.** That is the point of the Metal result: `newLibraryWithSource`
compiled MSL on a machine with no Xcode and no `xcrun metal`, and Accelerate and
MetalPerformanceShaders are part of the OS. The only files produced were the
`Mm2.class` / `W.class` baselines and a `native-image` build directory, all deleted.
