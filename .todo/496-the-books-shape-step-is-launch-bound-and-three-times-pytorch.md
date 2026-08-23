# The book's-shape step is launch-bound, and 3-4x PyTorch's eager step on the same card

Difficulty: High

Filed 2026-08-23 night, after `.todo/492` closed. Read `.kb/gpu.md` "A lazy result
allocates no host array" first -- its numbers are the starting line -- and the two rounds
before it. Everything measured on the GB10, JVM class output, `--gpu --simd`,
`java -Xmx64g -XX:+UseParallelGC -Xmn8g`.

## Where it stands

`train-gpt-soseki` at the book's own shapes (block 256, n_embd 384, 6 layers, 6 heads,
batch 64, the whole novel, 13.06 M parameters):

| | per step | the notebook's 5000 steps |
|---|---|---|
| rontolisp, 2026-08-23 morning (every result downloaded) | 9.9 s | projected 9 h |
| rontolisp, evening (lazy results, the index tier) | 6.3 s | -- |
| rontolisp, night (result stubs, `.todo/492`) | **0.84 s** (0.65 on the 13-step metric) | **70.7 min, measured** (loss 8.10 -> 0.24) |
| PyTorch 25.11 container, same model and shapes, eager fp32 | **0.243 s** | (20 min at that rate) |

So the port is within a factor of 3.5 of eager PyTorch on this card, and the step is about
1.2 TFLOP that the device finishes in ~0.2 s: the other ~0.6 s is launches, the link and
the host glue, not arithmetic. A profile of THIS build's step (nsys over a 13-step run,
plus a JFR of the host) is the first thing to take; until it exists the suspects, in the
order the earlier rounds would put them:

- **The `cuCtxSynchronize` after every big product** (`CudaGemm.syncFlopCeiling`, the
  `sync` flag in `launch`) predates lazy results: it existed so that a CRITICAL download
  would not sit inside a safepoint-free window for the kernel's whole runtime. Under lazy
  results the product is not downloaded at all, so the sync only idles the host while the
  device runs. Measure with it off under `lazy`; the safepoint reason needs to be
  re-argued, not dropped.
- **One launch per `linalg:` member.** `softmax` is six launches (`amax` fold, broadcast
  `sub`, `exp`, `sum` fold, broadcast `div` -- and the `where` behind the mask), `layer-norm`
  about as many, and attention per head and layer a dozen; at 64 x 6 x 256 x 256 every one
  is ~10 us of launch plus its memory pass. PyTorch eager pays the same launch count and
  is at 0.24 s, so launches alone do not explain the gap -- but fused `softmax` /
  `layer-norm` kernels (one pass, one launch) are what `torch.compile` buys, and the
  same measurement should say what they buy here.
- **Per-call allocation.** Every member still takes `cuMemAllocAsync` for its result and
  the pre-flight's `cuMemGetInfo` every `FREE_MEMORY_REFRESH_INTERVAL` calls; the lazy
  budget has no cap, so the pool's warm-block recycling that the cap bought (1 us an
  allocation against 5) is gone. Count them.
- **The host glue**: the autograd tape (`torch.lisp` conses a node per op), the boxed
  doubles of the hyper-parameters, `_gpuMaterialize`'s ring misses on loops over more
  than four arrays, `Invokers.checkCustomized` on the driver's handles (`.todo/476`), and
  the `System.gc()` the residency now asks for (2888 over 5000 steps, 132 s = 3%).
- **The collector.** At this shape ParallelGC beats the default collector by a third
  (103 steps: 101 s against 131); at the notebook's width it is the reverse. Find out
  why before the README has to say "it depends" (`.todo/498`).
- **Width.** PyTorch's bf16 / `torch.compile` figures are in the same log
  (`.kb/gpu.md`, the todo-492 section's last table); `.todo/482`-`490` is the bf16 plan.

## Acceptance

The step re-measured at the book's shapes with a profile that names where the 0.6 s
goes, the first two suspects tried and their effect recorded, and the README's
"PyTorch on the same machine" column re-read against the result.
