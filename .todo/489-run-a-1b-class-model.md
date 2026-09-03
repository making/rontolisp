# 489. Run a 1B-class model on rontolisp

Difficulty: High

The goal `.todo/482` exists for. Depends on `.todo/484`, `.todo/485`, `.todo/487`,
`.todo/488`.

`examples/llama2` runs `stories15M` today: 15M parameters, 60.8 MB of f32 weights, 339
tok/s single-thread on GB10 (`.todo/457`). The point of adding a narrow width is to move
that up two orders of magnitude -- to a model someone would actually ask a question.

## The two rungs

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
