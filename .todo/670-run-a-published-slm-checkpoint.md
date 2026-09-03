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
