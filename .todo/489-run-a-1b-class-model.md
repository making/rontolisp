# 489. Run a 1B-class model on rontolisp

Difficulty: High

The goal `.todo/482` exists for. Depends on `.todo/484`, `.todo/485`, `.todo/487`,
`.todo/488`.

`examples/llama2` runs `stories15M` today: 15M parameters, 60.8 MB of f32 weights, 339
tok/s single-thread on GB10 (`.todo/457`). The point of adding a narrow width is to move
that up two orders of magnitude -- to a model someone would actually ask a question.

## The rungs (re-ordered 2026-09-03: the user wants the newest models, e.g. Qwen 3.x)

Surveyed on Hugging Face that day. What "newest" means in practice: **Qwen3.5-0.8B**
(2026-06) is the newest Qwen with a small member -- Qwen3.6 and 3.8 ship only 27B+ and
3.8-27B has the same architecture -- and **LFM2.5-1.2B-Instruct** (2026-08-24) is the
newest ~1B model of any family. Both are hybrids, so each is an architecture item
(`.todo/677`, `.todo/678`) on top of the layer-kind table (`.todo/676`). The order below
is by increasing architecture delta, and every rung after 0 is a model people actually
download today:

| rung | model | bf16 | architecture work | item |
| --- | --- | --- | --- | --- |
| 0 | TinyLlama-1.1B / SmolLM2-135M, 360M | 2.2 GB / 0.27 GB | none (llama) -- shakes out the readers | below |
| 1 | `Qwen/Qwen3-0.6B` (2025-04) | 1.5 GB | QK-norm, tied head, vocab 151936 | `676` |
| 2 | `LiquidAI/LFM2.5-1.2B-Instruct` (2026-08) | 2.34 GB | 10 gated short-conv + 6 attention layers | `678` |
| 3 | `Qwen/Qwen3.5-0.8B` (2026-06) | 1.5 GB (+0.2 GB vision, skipped) | Gated DeltaNet x 18, gated attention, partial RoPE | `677` |
| 4 | `Qwen/Qwen3.8-27B` (2026-08) | 54 GB | none beyond rung 3; RAM (fits this box) and `--gpu` | `490`'s successor |

Rung 2 is "the 1B" whose numbers this item reports; rung 3 is the Qwen the user asked
for. The two original rungs below stay as rung 0.

## The two original rungs

1. **`stories110M`** (karpathy's llama2.c, same `.bin` format the loader already reads):
   110M parameters, 420 MB f32 / 220 MB bf16. An intermediate step that shakes out the
   bulk load path and the larger tensor shapes while staying small enough to keep in the
   examples suite.
2. **TinyLlama-1.1B** -- the goal. It is the llama2 architecture at 1.1B parameters, so
   `llama2.lisp`'s forward pass applies unchanged. 2.2 GB at bf16 against 4.4 GB at f32.
   **Its source is the published checkpoint, read as published** (2026-09-03):
   `TinyLlama/TinyLlama-1.1B-Chat-v1.0` is 201 BF16 tensors in one `model.safetensors`
   (`.todo/675`), and the same model is on Hugging Face as GGUF in BF16 / F16 / Q8_0
   (`.todo/673`). karpathy's `export.py` is NOT the route: it needs PyTorch, which this
   box does not have, and it would put a Python step between the model and the language
   when the readers make the file itself the input. Its vocabulary is Llama 2's 32000
   SentencePiece pieces, so llama2.c's `tokenizer.bin` is expected to apply -- verify by
   comparing it piece for piece against the GGUF's `tokenizer.ggml.tokens` before trusting
   a generation.

Two more rungs, added 2026-09-03, are the *llama-architecture* models the field actually
publishes small: **SmolLM2-135M and SmolLM2-360M** (`HuggingFaceTB/`, all BF16, GQA, tied
embeddings, `rope_theta` 100000) -- 135M is 270 MB at bf16 and a plausible examples-suite
fixture. Their tokenizer is GPT-2-style byte-level BPE, not SentencePiece, which is
`.todo/674`; the forward pass needs only what `llama2.lisp` already has plus the tied
classifier and the RoPE base as a parameter.

Llama-3.2-1B is deliberately not the target: different RoPE, GQA layout and tokenizer
would make this an architecture item rather than a width item.

## Measured at f32, and the prediction bf16 has to beat (2026-09-03, host dorian)

Two published checkpoints, read straight from their BF16 safetensors and widened to `#f`,
JVM class output under `--simd`, 64 greedy tokens, Xeon E5-2697A v4 (Broadwell, AVX2, 64
threads), GraalVM 25.0.4:

| | 1 thread | `--parallel` (64) | bytes read per token | parallel bandwidth |
| --- | --- | --- | --- | --- |
| Qwen3.5-0.8B | 2.00 / 2.48 tok/s | **8.56 tok/s** | 3.2 GB | **27.0 GB/s** |
| TinyLlama-1.1B | 1.58 / 1.91 tok/s | **6.97 tok/s** | 4.4 GB | **30.7 GB/s** |

**Two independent models landed on the same ceiling on the same box.** That is what makes
this a diagnosis rather than a pair of numbers: the parallel leg is bound by DRAM, not by
thread count -- 64 threads buy about 4x over one, because the weights have to cross the
bus once per token either way. The single-thread leg is at 8.4 and 5.0 GB/s, nowhere near
that ceiling, so it is bound by something else.

**The prediction, written before `.todo/485` lands so that measuring it is a test:**

- **The parallel leg roughly doubles** -- bf16 halves the bytes and the bytes are the
  limit. Qwen3.5-0.8B **~17 tok/s**, TinyLlama-1.1B **~14 tok/s**.
- **The single-thread leg does not move much.** It is not bandwidth-bound, so removing
  bandwidth cannot help it. `.todo/488`'s fused kernels are that leg's story, and they
  should show up here and NOT in the parallel column.
- **Load-in time drops too**: 8.7-9.9 s for TinyLlama today is 2.2 GB read and widened to
  4.4 GB; with a `#bf16` destination the widen disappears.

**What a miss would mean, which is the point of writing it down.** If the parallel leg
does not roughly double, either the bandwidth diagnosis is wrong or the bf16 path has a
limit that is not bandwidth -- both worth knowing. If the single-thread leg DOES jump,
the serial leg was not compute-bound after all and `.todo/488`'s premise needs re-reading.
Re-measure both models, both legs, on a quiet box, and record the result here beside the
prediction rather than in place of it.

## What the numbers should look like

Estimated, not measured -- the arithmetic is here so the first real run can be checked
against it rather than accepted uncritically. Decode reads every weight once per token, so
per-token cost is parameters / GEMV throughput. At the measured bf16 rate of 13.4 Gelem/s
single-thread (`.todo/482-bfloat16-a-narrow-width-that-pays/Worth.java`, 4096x4096):

| | single-thread | `--parallel` (20 threads) |
| --- | --- | --- |
| 1.1B at bf16 | ~12 tok/s | ~30-60 tok/s, bandwidth-bound |
| 1.1B at f32 | ~8 tok/s | and 4.4 GB resident |

If the real numbers come in far below these, the cause is more likely per-token allocation
than the kernels -- `llama2.lisp` calls `vec:matvec`, which allocates a fresh result vector
per call; at 22 layers x 7 projections that is a lot of garbage per token, and
`vec:matvec-into` already exists.

## Do

1. Get `stories110M` running end to end first, on the existing f32 path, then at bf16.
   Fix whatever the larger shapes break before adding a gigabyte.
2. A download script beside `download-stories15M.sh`, and an offline f32-to-bf16
   conversion step (or the streaming narrow-at-load from `.todo/487`, which avoids needing
   a second file at all).
3. TinyLlama-1.1B: conversion, load, generate. Report resident bytes, load time, and tok/s
   single-thread and `--parallel`, at bf16 and -- if it fits -- f32, so the width's
   contribution is visible rather than asserted.
4. Watch for the things that only appear at this size:
   - **Heap.** 2.2 GB of `short[]` needs the JVM run to say so; document the `-Xmx`. Check
     the native-image build too, which is how the fastest path runs. (Memory is a
     documentation matter at this size, not a feasibility one: 4.4 GB of f32 fits this
     box and any 8 GB laptop. The first end-to-end run can and should be at f32, through
     `.todo/671`'s bulk widening, before the width exists -- 7 tok/s projected on one
     thread, 21 on twenty -- so the loader and the model are debugged apart from the
     kernel.)
   - **Load time.** 2.2 GB through `read-sequence`; if it is minutes rather than seconds
     the bulk path from `.todo/487` is not actually bulk.
   - **The 2^31-1 array element cap** (`.todo/485`): TinyLlama's largest tensor is the
     32000 x 2048 embedding at 65.5M elements, well clear -- but check rather than assume,
     and record the real ceiling.
   - **Allocation per token**, per the note above.

## Testing

The 1.1B model is **not** an E2E fixture: the weights cannot go in the repository and a
generation run is far too slow for `ExamplesE2eTest`. Keep it a documented manual run with
a download script, explicitly outside `examples/examples.yaml`.

`stories110M` is the judgement call -- 220 MB is still too large to check in, but a
downloaded fixture with a short generation may be affordable in the examples suite. If it
is not, keep `stories15M` as the E2E-sized case and let both larger models be manual.

## Done means

A prompt goes in and coherent text comes out, from a 1.1B-parameter model, on rontolisp,
with the numbers above filled in for real and the `examples/llama2` README carrying them.
Output will not be token-identical to an f32 run and must not be asserted to be; bf16
weights are what the model was published as, so the bar is that the text reads correctly.
