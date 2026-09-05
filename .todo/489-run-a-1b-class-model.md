# 489. Run a 1B-class model on rontolisp

Difficulty: High

The goal `.todo/482` exists for. `.todo/484` and `.todo/485` closed 2026-09-03 and
`.todo/487` landed its step 1; the f32 rungs below are measured and no longer projections.
`.todo/488`'s wiring closed 2026-09-05, so the bf16 rungs are unblocked -- see the
precondition under the prediction for what it does and does not cover.

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

| rung | model | bf16 | architecture work | item | f32 run |
| --- | --- | --- | --- | --- | --- |
| 0 | TinyLlama-1.1B / SmolLM2-135M, 360M | 2.2 GB / 0.27 GB | none (llama) -- shakes out the readers | below | 2026-09-03 / 2026-09-05 |
| 1 | `Qwen/Qwen3-0.6B` (2025-04) | 1.5 GB | QK-norm, tied head, vocab 151936 | `676` | 2026-09-05 |
| 2 | `LiquidAI/LFM2.5-1.2B-Instruct` (2026-08) | 2.34 GB | 10 gated short-conv + 6 attention layers | `678` | `678`'s lane |
| 3 | `Qwen/Qwen3.5-0.8B` (2026-06) | 1.5 GB (+0.2 GB vision, skipped) | Gated DeltaNet x 18, gated attention, partial RoPE | `677` | 2026-09-03, re-measured 2026-09-05 |
| 4 | `Qwen/Qwen3.8-27B` (2026-08) | 54 GB | none beyond rung 3; RAM (fits this box) and `--gpu` | `490`'s successor | -- |

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

**The precondition, satisfied 2026-09-05 (`.todo/488`).** `.todo/485` added the ARRAY
TYPE and nothing more; until 488's wiring landed, `eval/VecSimd` declined a
`LispBFloat16Array` at every dispatch point and fell through to the scalar Lisp defun, so
a bf16 measurement was a measurement of the scalar path and neither confirmed nor refuted
anything below. It now fuses -- but over ONE pairing, which is the shape a decode step
has and the shape to write the rungs in: **bf16 weights against f32 activations**, in
`vec:sum`, `vec:dot` and `vec:matvec` / `matvec-into`. A bf16 ACTIVATION vector declines
to the defun and will measure slow; so does every element-wise `vec:` member over bf16
(`.todo/696`). The measured GEMV ratio against `#f` on this box is 1.49x under Graal and
2.00x under C2 at 4096x4096 one thread, and 0.72-0.84x while the matrix is cache-resident
(`.todo/488-the-fused-bfloat16-gemv-kernels/README.md`), so a model whose per-layer
matrices are small will not see the headline.

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

## Re-measured at f32 on a quiet box, and the rest of the f32 set (2026-09-05, dorian)

Provenance for every row: develop `2275c000` plus this item's `llama2.lisp` tokenizer
change (no kernel touched), JVM class output of `examples/llama2/llama2.lisp` under
`--simd` (and `--simd --parallel` for the threaded rows), GraalVM 25.0.4 with its Graal
JIT, `-Xmx16g`, 64 greedy tokens of the README's chat prompt, Xeon E5-2697A v4 (Broadwell,
AVX2, 64 hardware threads), **the thread count explicit on every `--parallel` row** --
a `--parallel` figure without one is not comparable to anything, and the missing count
on the 2026-09-03 rows is what made the question below worth two runs. **"Quiet box"
here means no other rontolisp LANE running, not no other load**: dorian carries steady
co-tenants with 47-day uptimes (`clickhouse-server` at ~17% of a core, `mysqld`, a
`bundle`, a `node`), its idle 1-minute load average is 0.3-0.9, and those were there
during the 2026-09-03 rows too -- a constant that cancels between two dorian runs and
does NOT cancel against GB10's 164 GB/s or any absolute GB/s claim. The other lane was
off the box for the whole block; the 1-minute load average at each run's start was
1.5-19, all of it the PREVIOUS run's own spinning workers decaying. The GB/s column is
the 32-thread tok/s (first run) times the bytes per token, nothing else; the bytes are
the parameter count times four. Load-in times are in the README.

| | 1 thread | 32 threads | 64 threads (default) | bytes / token | GB/s at 32 threads |
| --- | --- | --- | --- | --- | --- |
| Qwen3.5-0.8B | 3.06 | 9.18 / 9.13 | 8.92 / 8.71 | 3.2 GB | 29 |
| TinyLlama-1.1B | 1.86 | 8.84 | 8.17 / 8.01 | 4.4 GB | 39 |
| Qwen3-0.6B (rung 1, first run) | 2.45 / 2.18 (GGUF 2.21) | 9.72 / 9.00 | 9.17 / 8.37 | 2.4 GB | 23 |
| SmolLM2-360M-Instruct | 4.26 | 13.97 | -- | 1.4 GB | 20 |
| SmolLM2-135M-Instruct | 8.69 | 28.89 | -- | 0.54 GB | 16 |

**The 2026-09-03 rows are CONFIRMED, not superseded.** The suspicion tested was that
8.56 / 6.97 had been taken under lane contention (two lanes worked this box that day, and
a 64-thread run with one busy core beside it collapses 10x -- `.todo/697`) and were too
low, which would have flattered the bf16 prediction stated as their multiple. On the
quiet box Qwen3.5-0.8B at 64 threads is 8.7-8.9 against 8.56 (within 5%) and TinyLlama
8.0-8.2 against 6.97 (10-15% higher, not a collapse; 7.48 was the README's other run).
Contention did not produce those rows; the suspicion did not hold, and that is the whole
result. The single-thread rows moved more (3.06 against 2.00 / 2.48; the 09-03 ones were
taken at a load average of 13-21 by their own note), which is the direction a serial run
takes a busy box's DRAM traffic -- record it, do not build on it.

**"Two independent models on one ceiling" does not survive its own numbers.** 27.0 and
30.7 GB/s read as one wall; 39, 29, 23, 20 and 16 GB/s across five models on one quiet
box do not, and the order is the one `.todo/678`'s lane PREDICTED before any of it was
measured, from access shape: TinyLlama is plain llama with big matvecs and streams best,
Qwen3.5 spends 576 of its GEMVs per token on 128 x 128 Gated DeltaNet reads and streams
worse, and the small SmolLM2 models spend their token in the layer walk around their
576- and 960-wide products, not on the bus. That is 678's finding, measured here for a
different purpose; two lanes landing on it independently is why it is stated as more than
a hypothesis, and five models is still five points, not a law. What survives unchanged:
the parallel leg is DRAM-shaped (32 threads beat 64 on every row, and the serial leg is at
5-8 GB/s with the same weights), so "bf16 halves the bytes" still addresses the right
limit -- but the ceiling it is halved against is per model, so the doubling prediction
above has to be read against each model's OWN f32 parallel bandwidth, not against one
box number. Which is why the bf16 rows below carry Gelem/s and GB/s per arm, not tok/s
alone.

**The other rungs, first run 2026-09-05:**

- Rung 1, Qwen3-0.6B: runs from `Qwen/Qwen3-0.6B`'s safetensors and from unsloth's BF16
  GGUF, the same 64 tokens from both; `llama.cpp` on the GGUF opens with the same eleven
  words (then diverges on its bf16 kernels). It answers the chat prompt by thinking out
  loud through the empty `<think>` block, and so does `llama.cpp` -- the model's habit,
  not a template bug. `head_dim` 128 on a 1024-wide model, read from `config.json`.
- Rung 0, SmolLM2: 135M (base and Instruct) and 360M-Instruct from safetensors, after the
  reader learned to take the pre-tokenizer kind and the BOS rule from `tokenizer.json`
  itself rather than from the family row (SmolLM2 and TinyLlama are both `model_type`
  `llama`; only the file tells byte-level BPE from SentencePiece). The Instruct 135M
  continuing "Once upon a time" loops, and the loop is the oracle: the surviving F16 GGUF
  is that Instruct checkpoint and prints it token for token, and so does `llama.cpp` on
  that GGUF. Both are in the README with the commands.
- The correctness bar the rungs are now held to is TOKEN identity with `llama.cpp` at
  temperature 0 on a RAW completion (no chat template on either side, the prompt ids
  proven equal before any output is read): Qwen3.5-0.8B on GB10 (`.todo/677`, resolved
  2026-09-05 by the other orchestrator's lane -- the earlier "same character, different
  sentence" was the two chat harnesses, not arithmetic) and LFM2.5-1.2B on this box
  (`.todo/678`) both meet it, which makes it a two-architecture, two-box result. The
  SmolLM2-135M-Instruct loop above is the same kind of check, and Qwen3-0.6B meets it
  too: raw "Once upon a time" on the BF16 GGUF, `llama-completion -no-cnv --temp 0
  --repeat-penalty 1.0 --top-k 0 --top-p 1.0 --min-p 0` (dorian, x86 build of
  2026-09-03) against our f32 widening of the same file, the same 64 tokens of text
  ("there were 3000 people in a town. The number of people who are in the town is
  3000. ..."). The chat-mode comparison agreed only on its first eleven words, and the
  trace of that prompt found the reason: the reader matched whole only the added tokens
  flagged `"special"`, so Qwen's unflagged `<think>` went in as three ids. Fixed the same
  day in `examples/llama2/checkpoint-tokenizer.lisp` (both readers; failing-first pin
  `checkpoint-tokenizer-check.lisp` over a `tokenizers`-library fixture, all four
  backends), recorded in `.kb/tokenizers.md` and `.todo/701`. After it, every chat
  prompt's ids equal the Python library's for the rendered string (Qwen3.5 21 ids,
  Qwen3-0.6B 21, LFM2.5 18 and SmolLM2 18 -- the last two UNCHANGED by the fix, as the
  mechanism predicted: nothing in their templates for it to bite), and both Qwen models
  answer the chat prompt as the safetensors and the GGUF leg alike: Qwen3.5 "In the
  quiet, dusty corner of the old bakery, lived **Barnaby**...", Qwen3-0.6B "Once upon a
  time, there lived a cat named Luna..." -- no think-aloud preamble. `llama-cli`'s chat
  mode still thinks aloud on both, which is now a question about its template rendering
  against ours (`.todo/701`), not about ids.
- The interpreter leg of a `tokenizer.json` model died in this file's own byte-level JSON
  reader until `.todo/690` (2026-09-05) replaced that reader with `rontolisp:json-parse`;
  it runs now (SmolLM2-135M-Instruct chat, identical text to the JVM, 2.85 tok/s under
  `--simd`, `tokenizer.json` + KV cache 12.2 s against the JVM's 1.1 s). The builtin defect
  that killed it (`subseq` of an adjustable `(unsigned-byte 8)` buffer answers a
  `simple-vector` on every backend) is still `.todo/698`'s.
- Checkpoints: `/home/administrator/models/{qwen3-0.6b,qwen3-0.6b-gguf,smollm2-135m,
  smollm2-135m-instruct,smollm2-360m-instruct}` beside the ones the other lane restored
  there (`tinyllama`, `qwen35`, `smollm2-135m-f16.gguf`); the HF cache under
  `~/.cache/huggingface/hub` holds the same files. Not in the repo.

**The bf16 rungs (wave 2), and how they are to be read.** Three things are fixed before
the first number is taken:

1. **Activations are f32 by plan, permanently** -- `.todo/670` ("bf16 is a storage width
   for weights, and nothing here changes what an activation is") and `.todo/482`
   ("same f32 activations; only the weight format"). It is what `.todo/488`'s fused
   pairing is, and what `.todo/672`'s Q8_0 rows will be. **A bf16 activation vector
   observed anywhere in a rung's forward pass is a loader or forward-pass DEFECT to
   report and fix, not an explanation for a flat number.** (Whether the kernels could
   take another pairing is a plan question nobody has asked, not a wall in the code.)
2. **The single-thread expectation, restated.** The line above was written when 488's
   kernel was thought to be 1.6x; that figure is withdrawn (it was against a
   one-accumulator f32 baseline). The honest ratio is 1.49x under Graal and 2.00x under
   C2 at 4096 x 4096 one thread on GB10, and BELOW f32 while a matrix is cache-resident
   (about 4 MB of weights). So the serial leg should move by less than 1.5x on the big
   GEMVs and not at all on the small ones: Qwen3-0.6B's per-layer matrices are 6-13 MB of
   f32 (the 622 MB tied classifier dominates), TinyLlama's 8-46 MB, both above the
   crossover; Qwen3.5's 128 x 128 DeltaNet reads (64 KB) are not and will not see it.
   The old line stays above as written.
0. **The loader has no `#bf16` destination yet.** `safetensors:read` and `gguf:read`
   take `:element-type` `'single-float` (default) or `'double-float` and widen a BF16
   tensor through the checkpoint package's chunked widen; a `'bfloat16` target that
   copies the bits is `.todo/675`'s remainder ("waits on `487` steps 3-5"), and
   `llama2.lisp` then has to keep every ACTIVATION buffer at `#f` while the weight
   matrices are `#bf16` (point 1). Check both before the first timed run.
3. **Report Gelem/s and GB/s for each arm on each leg, and say which wall each is at**,
   with the f32 arm's bandwidth taken in the same window as the bf16 arm's. Where the
   loader can hold both widths in one process, the within-run ratio is the number to
   trust; an absolute from a non-quiet window is contaminated in bf16's favour (the f32
   arm is the more bandwidth-bound of the two, so a co-tenant costs it more). "Held" or
   "missed" is not to be written on tok/s alone.

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
