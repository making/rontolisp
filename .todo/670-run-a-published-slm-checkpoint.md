# 670. Run a published SLM checkpoint: what Hugging Face ships, loaded as shipped

Difficulty: High (the umbrella; the children are sized individually)

Filed 2026-09-03 from the re-verification of `.todo/482` (`bfloat16`). The measurement
record is `.todo/482-bfloat16-a-narrow-width-that-pays/README.md`, "Round 2"; this item
is the plan those numbers imply, and `.todo/482` stays the width half of it.

**The goal: a small language model that someone downloaded from Hugging Face runs on
rontolisp from the file they downloaded** -- no Python, no `export.py`, no conversion
step outside the language. `examples/llama2` runs karpathy's `.bin`, a format one
project writes; the models people actually run are published in two formats,
**safetensors** (bf16, one JSON header and raw tensors) and **GGUF** (F32 / F16 / BF16 /
Q8_0 / Q4_K_M, the tokenizer and the hyperparameters in the same file), and in the widths
those formats carry. Checked 2026-09-03: SmolLM2-135M, TinyLlama-1.1B-Chat and
Qwen2.5-0.5B are each 100% BF16 in `model.safetensors`; no current small model is f16;
the GGUF already in this box's cache is Q4_K_XL with a BF16 companion.

## What the measurements decided, width by width

| width | verdict | where |
| --- | --- | --- |
| **bf16** | THE width. 1.5-2.1x f32 on one thread (Graal / C2), 1.6x on 20; widening exact; every checkpoint is in it | `.todo/482` (483-490), unchanged |
| **IEEE f16** | not a width -- a **load-time conversion** into `#f` / `#bf16`. A fused f16 GEMV is 0.30-0.58x on either JIT; converting 1.1B elements costs 0.11-0.57 s | `.todo/671` |
| **Q8_0** (32 int8 + a scale) | a **read-only weight matrix** type with an integer-dot GEMV: 2.0x f32 / 1.15x bf16 on one thread, 1.9x on 20, a quarter of f32's bytes, 7.6e-3 GEMV error; what half the published GGUFs are | `.todo/672` |
| **Q4_0 / Q4_K** | not a CPU item: the nibble unpack is ALU-bound at 5.7 GB/s (1.1x f32 for 8.5% error). A device width | `.todo/490`'s successor |

And the two facts under all four: **the width is bandwidth, not fitting** -- 4.4 GB of
f32 fits this 121 GB box and an 8 GB laptop, and a 1B decode is ~7 / 12 / 14 tok/s on
one thread at f32 / bf16 / Q8_0, ~21 / 34 / 39 on twenty -- and **every kernel number is
JIT-dependent**: the spike's fused kernel fell to 0.20x under C2 from an inlining cliff,
so `.todo/488` now takes its numbers under both JITs.

## Children, and the order

| item | what | difficulty |
| --- | --- | --- |
| `671` | f16 and bf16 **bits** widened in bulk into an existing width, on every backend | Low |
| `673` | read a GGUF: metadata, tensor table, F32 / F16 / BF16 / Q8_0 tensors, tokenizer fields | Medium |
| `675` | read a safetensors file (+ `config.json`) | Low |
| `674` | the byte-level BPE tokenizer (SmolLM2, Qwen, Llama 3) from the GGUF fields or `tokenizer.json` | Medium |
| `672` | the Q8_0 quantized weight matrix and its integer-dot `vec:matvec` | High |
| `676` | the forward pass as a table of layer kinds: QK-norm, NoPE, gates, partial RoPE, multipliers (Qwen3, SmolLM3, Granite) | Medium |
| `677` | the Gated DeltaNet layer: Qwen3.5-0.8B, and with it every Qwen 3.5-3.8 dense model | High |
| `678` | the LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct, the newest ~1B model | Medium |
| `489` | the model rungs: TinyLlama / SmolLM2 (loader shakeout), Qwen3-0.6B, LFM2.5-1.2B, Qwen3.5-0.8B | High |

**Order: 671 -> 673 / 675 -> 674 -> 489 rung 0 at f32 -> 676 -> 678 -> 677 ->
`.todo/482`'s 483-488 -> 489 at bf16 -> 672 -> 490.** (676-678 are pure Lisp over the
readers and can overlap the width chain; they touch no Java.) The point of that order: 671 needs no new array type and lands on every
backend, which lets a BF16 checkpoint load into `#f` BEFORE the bf16 width exists, so
the readers and the model are debugged at f32 (4.4 GB, fits) with the kernels out of the
picture, and the width then halves a run that already works. 672 comes after the width
because its scalar oracle and its `dequantize` target are `#bf16`.

## Lanes for two orchestrators, three workers each (2026-09-03)

The user's constraints: the primary goal is Qwen3.5-0.8B, bf16 is wanted anyway for
weaker machines, at most three workers per box, and the work split so that one
orchestrator needs no GPU. The split falls out of the dependency graph: **A is the model
side and is pure Lisp plus one small Java item; B is the width side and is the only half
that touches `LinalgGpu` / the device.** A can run on any machine with a JDK and network
(the checkpoints are downloads); B runs on the GB10 box, where the parent runs Maven
ONE lane at a time (two Maven runs in one tree void both, and the box cannot absorb
concurrent builds) with each lane in its own worktree.

**Orchestrator A -- no GPU** (Lisp; Java only in 671):

| wave | lane A1 | lane A2 | lane A3 |
| --- | --- | --- | --- |
| 1 | `671` (bits -> `#f`, all backends) | `674` tokenizer as a NEW `tokenizers.lisp` -- does not edit `llama2.lisp` | `676` the layer-kind refactor of `llama2.lisp` against the `.bin` path (stories15M identical) |
| 2 | `675` then `673` (the readers share one staging loop; same worker) | `678` LFM2 (needs A1's reader + own tokenizer) | `677` Gated DeltaNet (needs A1's reader, A2's tokenizer, own table) |
| 3 | `489` rungs 0-1 at f32: TinyLlama, Qwen3-0.6B | `489` rung 2: LFM2.5-1.2B, numbers | `489` rung 3: Qwen3.5-0.8B, numbers |

Ownership that avoids merge conflicts inside A: A1 owns the readers and `671`'s Java;
A2 owns `tokenizers.lisp`; A3 owns `llama2.lisp` / `llm.lisp`. Wiring a tokenizer or a
reader into the model file is A3's job in wave 2, never A2's or A1's.

**Orchestrator B -- the GB10 box** (the width chain, and everything that can be split
off it before the array type exists):

| wave | lane B1 | lane B2 | lane B3 |
| --- | --- | --- | --- |
| 1 | `483` the exhaustive-switch refactor (touches `LinalgGpu`: needs the GPU suite green) | `487` step 1 only: `bfloat16-bits` / `bits-bfloat16` on all four backends, and `FloatText.bfloat16Text` (`484` step 4) -- pure functions, no array type | `488`'s kernels as standalone methods over a bare `short[]` in both kernel files, the fused == widen-then-f32 test, and the both-JIT bench harness (`Jit.java`'s shape); not yet intercepted |
| 2 | `484` then `485` | `486` (after 484; the `--gpu` / `--blas` decline arms need the device suite) then the rest of `487` (after 485) | `490` step 1-2: `gemv_bf16` PTX and the `GpuDevice` width, tested standalone against B3's CPU kernel as oracle |
| 3 | `488` wiring into the `--simd` / `--parallel` interception, both-JIT numbers | `672` Q8_0 (after 485; its f16 scales need A's `671`) | `490` integration: residency map, threshold, the cap |
| 4 | `489` at bf16: rungs 1-3 re-measured (needs A's wave 2) | README numbers, `.kb/vec.md` / `.kb/gpu.md` records | -- |

Hand-offs across the two: A pushes `671` first (B's `672` and any bf16-target loading
read through it); B pushes `485` (A's readers gain the `#bf16` target, one keyword);
A pushes `677` (B's wave 4 measures it). Nothing else crosses. `671` and `483` both
touch `Environment` at a few sites -- whichever lands second merges.

Per-lane sizing: A1 Low+Medium, A2 Medium, A3 Medium then High (677 wants a Fable-class
model); B1 Medium then High (485 is the crux), B2 Low then Medium, B3 Medium then High.

## What is deliberately not in the plan

- **Not an inference framework.** The forward pass stays one Lisp file; what changes
  (2026-09-03, after surveying what is actually published) is that the layer becomes a
  KIND with options (`.todo/676`) and two more kinds join it (`.todo/677`, `.todo/678`),
  because the newest small models are hybrids: Qwen 3.5-3.8 is 3 Gated DeltaNet layers
  per attention layer, LFM2.5 is 10 short-conv layers per 6 attention layers. Gemma 4
  (gated licence, KV sharing, per-layer embeddings) waits until asked for.
- **Not mixed-precision training.** `torch:` stays f32/f64; bf16 is a storage width for
  weights, and nothing here changes what an activation is.
- **Not the device.** `--gpu` declines every new type until `.todo/490` and its successor;
  declining correctly is what `.todo/483`'s exhaustive switches buy.
- **Not fp8 / int4 on the CPU.** Measured out (above); re-measure only when the Vector API
  grows a dot-product or a narrower conversion, or on a host whose JIT does better than
  1 op/element for the unpack.
