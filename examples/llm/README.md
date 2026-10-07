# A language model engine in rontolisp

[Andrej Karpathy's llama2.c](https://github.com/karpathy/llama2.c) `run.c`,
ported whole to one Lisp file: the checkpoint loader, the SentencePiece-style
tokenizer with its BPE encoder, the Llama 2 forward pass (RMSNorm, RoPE,
multi-head causal attention over a KV cache, SwiGLU, the classifier head), the
temperature / top-p sampler with run.c's own xorshift generator, and the
generate loop. Given a checkpoint the C program reads, it tells the same
stories -- token for token, at temperature 0 and at any seed. The forward pass
is written as a [table of layer kinds](#the-layer-table), so a family that
differs from Llama 2 is a row of options rather than a fork of the file.

The 1 MB `stories260K.bin` + `tok512.bin` pair is checked in (from
[karpathy/tinyllamas](https://huggingface.co/karpathy/tinyllamas), MIT). The
model the llama2.c README demos, `stories15M.bin` (60 MB), is one script away:

```bash
./download-stories15M.sh          # stories15M.bin + tokenizer.bin, into this directory
```

## Running

The knobs are run.c's own flags, read with `uiop:command-line-arguments`. Each
one falls back to an `LLAMA2_*` environment variable, for a host that hands the
program no command line (a browser shim, an embedder):

| run.c flag | variable | default |
| --- | --- | --- |
| the positional checkpoint | `LLAMA2_CHECKPOINT` | `stories15M.bin` |
| `-z` | `LLAMA2_TOKENIZER` | `tokenizer.bin` |
| `-i` | `LLAMA2_PROMPT` | empty |
| `-n` | `LLAMA2_STEPS` | 256 |
| `-t` | `LLAMA2_TEMPERATURE` | 1.0 (0 = greedy) |
| `-p` | `LLAMA2_TOPP` | 0.9 |
| `-s` | `LLAMA2_SEED` | the clock |
| `-m` | `LLAMA2_MODE` | `generate` (continue the prompt); `chat` wraps it in the family's chat template |
| `-w` | `LLAMA2_WEIGHTS` | `f32` (a bf16 / f16 checkpoint is widened as it is read); `bf16` keeps the file's own bits for the weight matrices -- the norms and every activation stay f32 either way |
| -- | `LLAMA2_TRACE` | set to anything: every token id and its text on stderr |

From this directory, on all four backends. The interpreter takes the program's
own arguments after `--` (everything before it is the compiler's); a compiled
artifact takes them straight after itself:

```bash
ARGS='stories15M.bin -t 0 -i "Once upon a time"'

rontolisp llm.lisp --simd -- $ARGS                            # interpreter
rontolisp llm.lisp -o Prog.class --simd && \
  java --add-modules jdk.incubator.vector Prog $ARGS
rontolisp llm.lisp -o Prog.class --gpu --simd && \
  java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog $ARGS  # + an NVIDIA GPU
rontolisp llm.lisp -o llm.wasm --simd && \
  wasmtime run --dir . llm.wasm $ARGS
rontolisp llm.lisp -o llm.wasm --simd --component && \
  wasmtime run --dir . llm.wasm $ARGS
```

Its `ExamplesE2eTest` slice is `-Drontolisp.examples.only=llm/` **with the
slash**: `only=llm` is a plain substring match on the example's path, so without
it the slice also pulls in `ml/tiny-llm.lisp` and the whole of
`llm-from-scratch/` -- 72 legs where this directory alone is 35.

Every one of them prints

```
Once upon a time, there was a little girl named Lily. She loved to play outside in the sunshine. One day, she saw a big, red ball in the sky. It was the sun! She thought it was so pretty.
Lily wanted to play with the ball...
```

which is what `./run stories15M.bin -t 0 -i "Once upon a time"` prints -- the
whole 256-token story is byte-identical, and so is the command line. The small
model runs the same way with `stories260K.bin -z tok512.bin`.

## A Hugging Face checkpoint

**Every `tok/s` figure on this page was printed before 2026-09-07, by a harness that put
the prompt's forward passes in the clock and not in the count, so each sits BELOW the rate
its loop was decoding at.** `achieved tok/s` used to start its clock at the end of the
first loop iteration -- a prompt position, in every run with a prompt -- and divide by it
the positions the model SAMPLED. The 21-id chat prompt of the tables below makes 64 tokens
over 84 forward passes, 0.76 of the forward rate; TinyLlama's raw 5-id "Once upon a time"
0.94 of it. The head of a run is 1.5-2x slow while the JIT warms besides (and under
`--gpu` pays the context creation and the weight upload), which a 64-token average carries
and a longer run dilutes. Since 2026-09-07 the clock starts in front of the first forward
the model samples from -- generated tokens over the forwards that generated them -- and a
second figure beside it, `(last N: ...)`, is the rate over the run's second half, which is
the steady one. **The numbers below are left as they were measured**: within a table every
row was printed the same way on the same prompt, so they compare with each other -- not
with a forward rate, and not across tables whose prompt lengths differ.

The positional checkpoint may also be the DIRECTORY a Hugging Face model page
downloads to -- `config.json` beside `model.safetensors` (or the sharded
`model.safetensors.index.json`) -- read by the shipped
[`safetensors:`](../../doc/en/reference/functions/safetensors.md) package into the
same model the `.bin` loader builds. No Python, no conversion: the BF16 (or F16
/ F32) tensors are widened into packed single-float arrays as they are read,
staged a million elements at a time, so a 2.2 GB file needs its 4.4 GB of
weights and a few MB besides. `load-hf-checkpoint` does what is per FAMILY --
the tensor names, Qwen3.5's `1 + w` norms and `-exp(A_log)` and query | gate
interleave, LFM2's `operator_norm` and `layer_types` -- and hands the table the
shapes it expects; `model_type` picks the `*architectures*` row, and every HF
layout is `:rope :halves`. `max_position_embeddings` is capped at 4096 for the
KV cache (`*seq-len-cap*`), and generation stops on the config's `eos_token_id`
as well as on llama2.c's BOS.

The tokenizer beside the weights is read the same way: `tokenizer.json`'s own
`pre_tokenizer` block says which byte-level BPE scanner the file wants (a
`Digits` step before `ByteLevel` is SmolLM2's, a `Split` regex is told apart
by its number clause -- `\p{N}{1,3}` Llama 3, `\p{N}` Qwen, `[\p{L}\p{M}]+`
Qwen 3.5), and its `post_processor` (or `tokenizer_config.json`'s
`add_bos_token`) says whether a BOS is prepended -- SmolLM2 names
`<|im_start|>` as its `bos_token` and adds none. That is per FILE, not per
family: SmolLM2 and TinyLlama are both `model_type` `llama`, and TinyLlama's
`tokenizer.json` is SentencePiece under the same `"BPE"` model type, with no
`ByteLevel` step, which sends the loader to `tokenizer.bin`. Both readers --
this one and the GGUF's -- live in [`checkpoint-tokenizer.lisp`](checkpoint-tokenizer.lisp),
and both match EVERY added token whole, flagged `"special"` or not (a GGUF's
token types 3 and 4), because the reference implementation does: Qwen3 ships
`<think>` and `</think>` unflagged, and a reader that took only the flagged
ones fed a chat prompt's think block as `<th` `ink` `>`, three ids for one.
[`checkpoint-tokenizer-check.lisp`](checkpoint-tokenizer-check.lisp) pins both
readers' ids against the Python `tokenizers` library over the fixture
[`tokenizer-fixture.py`](tokenizer-fixture.py) writes, on all four backends.

TinyLlama-1.1B-Chat uses the Llama 2 tokenizer -- the same 32000-entry
`tokenizer.bin` the stories do:

```bash
# download config.json + model.safetensors (2.2 GB) into a directory, then
rontolisp llm.lisp --simd -- TinyLlama-1.1B-Chat-v1.0 -z tokenizer.bin -t 0 -n 40 -i "Once upon a time"
```

prints, from the BF16 file, `Once upon a time, there was a young woman named
Lily. She lived in a small town, where everyone knew each other's names. Lily
was a kind and gentle soul, always` -- identical on the interpreter and the JVM
class output. Measured on the JVM class output (develop `116e8c55`, GraalVM
25.0.4, a 64-thread Xeon E5-2697A v4 -- Broadwell, AVX2 -- at a load average of
6-27; two runs each):

| | tok/s | per token |
| --- | --- | --- |
| `--simd`, one thread | 1.91 / 1.58 | 8.4 GB/s of weights |
| `--simd --parallel`, 64 threads | 7.48 / 6.97 | 30.7 GB/s |

The load is 8.7-9.9 s (2.2 GB of bf16 into 4.4 GB of f32; the tokenizer and
the KV cache another 0.4-0.6 s). The reading: a token streams every weight,
1.1B x 4 bytes = 4.4 GB, so the parallel row is at the box's DRAM bandwidth --
64 threads buy 4x not because the GEMV stops scaling but because the memory
bus is the ceiling; the single thread, at 8.4 GB/s, is not bandwidth-bound.
Halving the bytes (bf16 weights, `.kb/bfloat16.md`) is the lever, not
more threads.

The same model as a GGUF -- one file, its tokenizer inside, read by the shipped
[`gguf:`](../../doc/en/reference/functions/gguf.md) package -- prints the same 40
tokens (`Llama TinyLlama-1.1B-Chat-v1.0-f16.gguf -t 0 -i "Once upon a time"`):
a `convert_hf_to_gguf.py` conversion permutes Q and K into llama.cpp's adjacent-
pair RoPE layout, which is `:pairs`, the layout the `.bin` loader has always
used. And `stories15M.bin` converted with `llama.cpp`'s
`llama-convert-llama2c-to-ggml` tells the 60-token story above token for token
-- `run.c`'s own text, out of a GGUF.

### Qwen3.5-0.8B

The first hybrid: 18 Gated DeltaNet blocks and 6 gated-attention blocks
([the layer table](#the-layer-table)), read from `Qwen/Qwen3.5-0.8B`'s BF16
safetensors (the index names one shard; the vision tower and the speculative
head are skipped by prefix) with its own `tokenizer.json` through the shipped
[`tokenizer:`](../../doc/en/reference/functions/tokenizer.md) package -- no
`tokenizer.bin` involved. `-m chat` wraps the prompt in the family's chat
template with thinking off, and prints the answer alone:

```bash
rontolisp llm.lisp -o Llama.class --class-name Llama --simd
java --add-modules jdk.incubator.vector -Xmx16g Llama Qwen3.5-0.8B -m chat -t 0 -n 64 \
  -i "Tell me a short story about a cat."
```

```
In the quiet, dusty corner of the old bakery, lived **Barnaby**, a cat with a coat of soft, burnt-orange fur and a tail that twitched when he felt the wind. Barnaby was not
```

The same model as ggml-org's `Qwen3.5-0.8B-BF16.gguf` -- one file, the tokenizer
inside it, read by the shipped
[`gguf:`](../../doc/en/reference/functions/gguf.md) package -- answers the same
prompt with the same text, token for token, and needs no `tokenizer.json`
(`Llama Qwen3.5-0.8B-BF16.gguf -m chat ...`). The publisher's `Q8_0` file loads
too, its weight matrices staying quantized (`rontolisp:quantize`'s type, 0.83 GB
of Q8_0 blocks read straight into place) and its GEMVs running the integer-dot
kernel. The prompt's 21 ids are the Python `tokenizers` library's for the same
rendered string, and on a RAW completion -- no chat template on either side,
"Once upon a time" -- the model's 64 ids are `llama.cpp`'s on the same BF16
GGUF, token for token; the Q8_0 file agrees with it for 60 tokens
and then picks a different word (two Q8_0 kernels are two fold orders of the
same 7.6e-3 quantization error, and this one is the scalar defun's bits, not
ggml's; the method, the ids and the numbers are in `.kb/quantized-matrix.md`).
`-m chat`'s rendered prompt is byte-identical to `llama-cli`'s own served
prompt for the same checkpoint (diffed against a Python `jinja2`
rendering of `tokenizer_config.json`'s `chat_template` and against
`llama-cli`'s served prompt directly): `llama-cli --reasoning-budget 0` still
opens a `[Start thinking]` block on this model because that flag is a
generation-time token budget, not the template's `enable_thinking` switch --
`llama-cli --reasoning off` is what maps to it, and with that flag the two
prompts match token for token, empty `<think>` block included.

Measured on the same box as the TinyLlama rows, JVM class output, f32 weights
(the load line: 7.1-7.6 s for 1.75 GB of bf16 into 3 GB, of which
`tokenizer.json` + the KV cache 2.4-2.7 s; from the GGUF 7.2 s, its tokenizer
0.75 s):

| | tok/s | loadavg |
| --- | --- | --- |
| `--simd`, one thread | 2.00 / 2.48 | 13.2 / 21.0 |
| `--simd --parallel`, 64 threads | 8.56 | 15.1 |

The parallel row is 3.2 GB x 8.56 = 27 GB/s, the DRAM ceiling once more --
two independent models on the same box land on the same wall:

```
Qwen3.5-0.8B     --simd --parallel   8.56 tok/s x 3.2 GB = 27 GB/s
TinyLlama-1.1B   --simd --parallel   6.97 tok/s x 4.4 GB = 31 GB/s
```

so the parallel leg is bandwidth-bound, not a property of one model, and the
prediction for bf16 weights (`.kb/bfloat16.md`) is close to twice
these rows, because they halve the bytes a token streams. Two
things the real checkpoint taught that its `config.json` does not say: the
vocabulary is 248070 (`vocab_size` 248320 is the padded embedding table, so
the sampler chooses among the tokenizer's ids only), and the answer ends at
`tokenizer_config.json`'s `<|im_end|>`, not at `eos_token_id`'s
`<|endoftext|>` -- both stop generation, and neither does when it is part of
the prompt. `tokenizer.json` (13 MB) is read by a byte-level JSON reader of
this file's own, because `rontolisp:json-parse` over that text does not finish
(`.kb/string-index-cost.md`).

### LFM2.5-1.2B-Instruct

The second hybrid, and the simplest one in the field: ten of sixteen blocks are
a **gated short convolution** and six are attention with QK-norm
([the layer table](#the-layer-table)). The conv block has no matrix state at
all -- one projection to `B | C | x'`, a causal depthwise convolution of kernel
3 over the gated input `B * x'`, a gate by `C`, and a projection back
(`shortconv.lisp`, over the `causal-conv.lisp` step it shares with Gated
DeltaNet). Its state is the previous two input vectors, 2 x 2048 floats a
layer.

`LiquidAI/LFM2.5-1.2B-Instruct` reads from its BF16 `model.safetensors` -- one
file and no index, which the reader takes as readily as a sharded set -- and
from Liquid's OWN `LFM2.5-1.2B-Instruct-BF16.gguf`, published by the model's
maker rather than converted by a third party:

```bash
rontolisp llm.lisp -o Llama.class --class-name Llama --simd
java --add-modules jdk.incubator.vector -Xmx24g Llama LFM2.5-1.2B-Instruct \
  -m chat -t 0 -n 64 -i "Tell me a short story about a cat."
```

```
Once upon a time, in a quiet little village, there lived a curious cat named
Whiskers. Whiskers wasn’t like the other cats—she had a knack for finding the
most unexpected things. One sunny morning,
```

The two files agree token for token, in `-m chat` and in plain continuation.
A `Q8_0` file is refused by name at `token_embd.weight` until the quantized
weight matrix exists.

**And `llama.cpp` on that GGUF prints the same bytes.** Same prompt, same
temperature 0, the whole overlap identical -- 581 characters (585 UTF-8
bytes), counted over a
stated window (from the first generated byte to the end of the shorter of the
two outputs; that is the exact figure, where the ~143 tokens it re-encodes to
is not, since a byte-level BPE re-encode of decoded text need not reproduce
the sequence that generated it). Qwen3.5-0.8B
was then checked the same way on the other box and is token-identical to
`llama.cpp` too, so this is **two models, two architectures, two machines**,
not one lucky prompt -- and it is not the byte-identity check a `Q8_0` file
will get, whose subject is the quantized kernel rather than the forward pass.

What the real checkpoint taught, none of it in `config.json`:

- **The GGUF names the Llama 3 pre-tokenizer after itself.**
  `tokenizer.ggml.pre` is `lfm2`, though `tokenizer.json` holds the Llama 3
  pattern character for character (`llama.cpp` maps it the same way, beside
  `llama-v3` and `llama-bpe`). An unmapped alias is refused by name, so the
  model does not load at all -- and the safetensors path, which reads the
  pattern itself, never sees it. The alias now maps; a family's own name for a
  shape it merely shares is accepted wherever a keyword is
  (`.kb/tokenizers.md`).
- `block_ff_dim` is not in the file: `intermediate_size` 12288 is the figure
  BEFORE `block_auto_adjust_ff_dim`, and the width the weights actually have is
  8192 (`2/3 x 12288`, rounded to `block_multiple_of`). The GGUF states the
  adjusted 8192 outright as `lfm2.feed_forward_length`.
- The GGUF gives the layer pattern as the per-layer `lfm2.attention.head_count_kv`
  array `#(0 0 8 0 0 8 0 0 8 0 8 0 8 0 8 0)` -- a zero KV-head count is a conv
  block -- where `config.json` gives it as `layer_types`. Both reach the table
  as `:layer-types`.
- The norms are named `operator_norm` / `ffn_norm` and the final one
  `embedding_norm`; attention output is `self_attn.out_proj`, the MLP
  `feed_forward.w1/w2/w3`, and QK-norm `q_layernorm` / `k_layernorm`.
- `vocab_size` 65536 is padded again: `tokenizer.json` defines 64909 ids
  (64400 + 509 added), and the sampler chooses among those.
- The stop token is `<|im_end|>` (7), which `config.json` states as
  `eos_token_id` directly -- unlike Qwen3.5, where it is only in
  `tokenizer_config.json`.

Measured on the same box as the rows above -- JVM class output, `--simd`, f32
weights, `-Xmx16g`, `-m chat -t 0 -n 64`, GraalVM 25.0.4 on JDK 25. Load 9.0-9.8 s
from the GGUF (its tokenizer 0.32-0.34 s) and 8.9-9.1 s from the safetensors
(`tokenizer.json` + the KV cache 1.54-1.67 s). Medians of 3-7 runs, spread in
brackets; "quiet" on this box means no other LANE, since it carries steady
co-tenants (`clickhouse-server` ~17% CPU, `mysqld`) that were running for every
row on this page:

| threads | LFM2.5-1.2B | Qwen3.5-0.8B, same window |
| --- | --- | --- |
| 1 | 2.13 (1.87-2.20) | 2.95 (2.54-2.99) |
| 8 | 7.21 (7.16-7.22) | 7.76 (7.74-7.88) |
| 16 | 8.83 (8.79-8.86) | 8.76 (8.68-9.05) |
| 32 | 9.30 (9.21-9.64) | 8.71 (8.57-8.75) |

**The two models scale differently, and that is the interesting row.** Between 16
and 32 threads LFM2.5 gains 5% while Qwen3.5 stays flat: Qwen3.5 is saturated
by 16 threads and LFM2.5 is still climbing at 32. Both ratios come from
cells whose own spread is under 2%, so neither depends on the noisier one-thread
figure. At ONE thread the two models are indistinguishable per byte moved (about
9-10 GB/s each, with overlapping spreads); the difference is entirely in how far
each scales. So the parallel leg is not simply the memory wall it looks like from
a single model -- dispatch and barrier cost rises with thread count, and a model
whose token is 576 small 128 x 128 GEMVs pays far more of it than one whose token
is thirty large matvecs. Four models is not yet a law, and `--parallel`'s default
of one thread per core is not the fastest setting for either of these.
### Qwen3-0.6B

The dense Qwen: 28 blocks of GQA attention with QK-norm (`head_dim` 128 on a
1024-wide model -- `config.json` says so and the loader believes it over
`hidden_size / heads`), a tied 151936 x 1024 classifier, the `qwen3` row. Read
from `Qwen/Qwen3-0.6B`'s BF16 safetensors with its `tokenizer.json`, and from
unsloth's `Qwen3-0.6B-BF16.gguf` with the tokenizer inside it, the same prompt
as above (`-m chat -t 0 -n 64`) gives the same 64 tokens from both:

```
Once upon a time, there lived a cat named Luna. She was small and fluffy, with a curious heart. One day, she found a hidden treasure in the forest. As she explored, she discovered a magical book
```

Before the added-token fix above the same command printed the model thinking
out loud ("Okay, the user wants a short story about a cat. Let me start by
brainstorming...") -- its empty `<think>` block had gone in as three tokens,
so it was answering a different prompt. With no template at all
(`llama-completion -no-cnv --temp 0 --repeat-penalty 1.0 --top-k 0 --top-p 1.0
--min-p 0` against `Llama Qwen3-0.6B-BF16.gguf -t 0 -n 64 -i "Once upon a
time"`) `llama.cpp` and this file print the same 64 tokens: `Once upon a time,
there were 3000 people in a town. The number of people who are in the town is
3000. ...`; in chat mode `llama-cli --reasoning-budget 0` still thinks out
loud on this model, but that flag does not turn the template's
`enable_thinking` off -- `llama-cli --reasoning off` does, and with it the
served prompt matches this file's rendered one token for token, empty
`<think>` block included. Measured on dorian (JVM class output, f32 weights, develop
`2275c000`, GraalVM 25.0.4, no other rontolisp run on the box -- its steady
co-tenants, a `clickhouse-server` at ~17% of a core and a `mysqld`, keep the
idle 1-minute load average at 0.3-0.9; the `loadavg` column is that figure
at the start of each run, and the values above idle are the previous run's
own worker threads decaying. It is recorded because on this box it is the
number that decides the `--parallel` row -- see below):

| | tok/s | loadavg |
| --- | --- | --- |
| `--simd`, one thread | 2.45 / 2.18 (2.56 on the idle box before the day started) | 17.1 / 9.8 |
| `--simd --parallel`, the default (32 threads, half of 64) | **9.81 / 9.00** | 5.2 / 7.2 |
| `--simd --parallel`, `RONTOLISP_THREADS=32` | 9.72 / 9.00 / 9.42 | 3.7 / 6.2 / 7.8 |
| `--simd --parallel`, `RONTOLISP_THREADS=64` (the whole box) | 9.17 / 8.37 / 8.70 | 7.7 / 16.2 / 5.7 |

The third value in each `--parallel` row and the default row were measured on
2026-09-06 at `24d4dd80`, when the default became half the processors
(`.kb/simd-parallel.md`); the first two are the 2026-09-05 pair above. The rest of the
sweep that day, same conditions, one run each: 9.55 tok/s at 16 threads, 7.58 at
8, 5.50 at 4, 2.27 at 1. **The curve is flat from 16 to 32 and bends down at
64** -- a GEMV is bandwidth-bound long before the last core, so the second half
of this box's threads adds nothing and costs a spinner, which is why the default
is half.

The load: 5.7-6.2 s for 1.5 GB of bf16 into 2.4 GB of f32, of which
`tokenizer.json` (11 MB) + the KV cache 2.6-3.0 s; from the GGUF 5.5 s, its
tokenizer 1.0 s. A token streams 0.6B x 4 = 2.4 GB, so 5.8 GB/s on one thread
and 23 GB/s on 32. Re-measured the same day with the thread count explicit,
TinyLlama reaches 39 GB/s on 32 threads (8.84 tok/s x 4.4 GB) and Qwen3.5-0.8B
29 (9.18 x 3.2), so "the DRAM wall" is a per-model figure on this box, ordered
by how the model streams its weights -- TinyLlama's big plain matvecs best,
Qwen3.5's 576 small Gated DeltaNet reads per token worst -- which is what
the access shape predicted before any of it was measured (`.kb/simd-parallel.md`).

**Why the whole-box row is a trap, and what it really costs.** The rows of a
GEMV are handed out to spinning workers and the caller waits for the last one,
so a worker descheduled mid-leaf holds every GEMV for a scheduler quantum; with
a thread per hardware thread, anything else runnable on the box lands in the
middle of a leaf. Measured 2026-09-05 with a six-core build running beside it:
**0.62 tok/s at 64 threads against 9.88 at 32**, and Qwen3.5-0.8B 0.83 where
the table above says 8.56.

That collapse is a TAIL, not the mean, and it takes real oversubscription. On
2026-09-06 it reproduced once -- two copies of this decode loop, 64 threads
each, five seconds apart: 0.57 and 3.38 tok/s -- and not at all in the other
attempts that day: 6, 16 and 64 pure-CPU spinner threads beside a single 64-thread
run gave 8.68 / 8.23 / 7.00, a maven build beside it 8.76, and three more pairs
8.60/8.44, 8.04/8.51 and (at the new default) 8.75/9.43. So the whole-box count
is not reliably slow; it is reliably EXPOSED, and one run in four paid for it.
The default is now half the processors, which is both faster on an idle box and
what makes a second lane cost nothing; `RONTOLISP_THREADS` still overrides it
(`.kb/simd-parallel.md`).

### SmolLM2

`HuggingFaceTB/SmolLM2-135M` (base and `-Instruct`) and `SmolLM2-360M-Instruct`
are `model_type` `llama` -- GQA, `rope_theta` 100000, tied embeddings -- so the `llama` row
runs them unchanged; what they bring is the GPT-2-style byte-level BPE
`tokenizer.json` above (the `:smollm` scanner, digits split one at a time)
and a ChatML chat template that the family row does not carry, so `-m chat`
uses ChatML whenever the vocabulary has `<|im_start|>`. **That fallback used to
render `*chatml*` -- no system turn at all** -- but the checkpoint's own
`tokenizer_config.json` chat template unconditionally opens with
`<|im_start|>system\nYou are a helpful AI assistant named SmolLM, trained by
Hugging Face<|im_end|>\n` whenever the first message is not already one, so
every SmolLM2-Instruct chat answer was missing its system turn. A
diff against the checkpoint's own template (Python `jinja2` over
`tokenizer_config.json`, and the identical field inside the GGUF; both agree
with `llama.cpp`'s own served prompt) is what found it; the fallback now
renders `*chatml-smollm2*`, which carries the system turn, and `llama.cpp` and
this file produce the same 64 tokens greedy from the checkpoint's own BF16
safetensors and F16 GGUF alike:

```bash
java --add-modules jdk.incubator.vector Llama SmolLM2-135M-Instruct -m chat -t 0 -n 64 \
  -i "Tell me a short story about a cat."
# Once upon a time, there was a cat named Whiskers. Whiskers was a curious and playful cat who loved to
java --add-modules jdk.incubator.vector Llama SmolLM2-135M -t 0 -n 48 -i "Once upon a time"
# Once upon a time, there was a little girl named Lily. She lived in a big house with her family, ...
```

The Instruct checkpoint continuing "Once upon a time" loops ("I was a young
man, a young woman, and a young man again"), and that loop is the oracle: the
F16 GGUF of the same Instruct model prints it token for token, and so does
`llama.cpp` on that GGUF (`llama-completion -no-cnv --temp 0`) -- three
readers of two files agreeing on 48 tokens. A tokenizer that adds no BOS
starts the prompt with a word, which `run.c`'s print loop never showed (it
prints each token as it is fed back in, and the first is always BOS there);
the loop now echoes it. Same box, same day, f32 weights, JVM class output:

| | 135M, one thread | 135M, 32 threads | 360M, one thread | 360M, 32 threads |
| --- | --- | --- | --- | --- |
| tok/s | 8.69 | **28.9** | 4.26 | **14.0** |
| loadavg | 18.2 | 15.9 | 16.5 | 12.3 |

At 0.54 GB (135M) and 1.4 GB (360M) of f32 per token these are not bandwidth
rows; the 135M model spends its token in the 30-layer walk around its
576-wide GEMVs, which is where `.kb/jvm-typed-loops.md`'s work sits, not the
weight width's.

### bf16 weights: `-w bf16`

Every checkpoint above is published in bf16, and `-w bf16` keeps the file's own bits
for the weight matrices -- half the bytes a token streams, no widen at load -- while
the norms, the biases, the KV cache and every activation stay f32 (`--simd` fuses
exactly that pairing, bf16 weights against f32 activations, in `vec:dot` /
`vec:matvec`; interpreter and JVM only). The text is the same as at f32 on every
model below, token for token, over 156 runs. Measured on dorian (develop `b87aed25`,
JVM class output, `--simd` for one thread and `--simd --parallel` with
`RONTOLISP_THREADS` explicit otherwise, `-Xmx24g`, GraalVM 25.0.4's Graal JIT, `-t 0
-n 64` of the chat prompt -- TinyLlama on the raw "Once upon a time", see the note --
the f32 and bf16 arms interleaved run by run, medians of 3, no other rontolisp lane on
the box; the load is the weights alone):

| model | 1 thread f32 -> bf16 | 16 threads | 32 threads | load f32 -> bf16 |
| --- | --- | --- | --- | --- |
| Qwen3.5-0.8B | 2.92 -> 3.44 (1.18x) | 9.47 -> 12.21 (1.29x) | 9.01 -> 12.01 (1.33x) | 6.9 -> 5.2 s |
| TinyLlama-1.1B | 2.26 -> 2.85 (1.26x) | 8.47 -> 12.36 (1.46x) | 8.80 -> 12.04 (1.37x) | 8.1 -> 4.4 s |
| LFM2.5-1.2B | 2.14 -> 2.52 (1.18x) | 8.57 -> 13.69 (1.60x) | 9.19 -> 15.14 (1.65x) | 8.5 -> 5.0 s |
| Qwen3-0.6B | 2.52 -> 2.77 (1.10x) | 9.47 -> 11.72 (1.24x) | 9.50 -> 12.41 (1.31x) | 5.1 -> 3.1 s |
| SmolLM2-360M | 4.19 -> 4.87 (1.16x) | 14.84 -> 17.65 (1.19x) | 14.25 -> 16.91 (1.19x) | 2.8 -> 1.5 s |
| SmolLM2-135M | 8.36 -> 9.70 (1.16x) | 28.99 -> 29.77 (1.03x) | 28.09 -> 29.01 (1.03x) | 1.4 -> 0.8 s |

Two readings. **The parallel leg does not double, and the thread count at which each
model stops scaling is the same at both widths** -- flat by 16 for the Qwens, TinyLlama
and SmolLM2, still climbing at 32 for LFM2.5 -- so the parallel cap is the parallel
machinery (work distribution, barrier cost), not the memory bus: halving the bytes a
token streams would have moved a bandwidth knee, and it did not move. The model that
scales furthest (LFM2.5, thirty large matvecs per token) gets the most from the width,
and a model whose token was never on the bus (SmolLM2-135M) gets nothing on the
parallel leg. **The serial leg moves 1.1-1.3x** (1.25-1.38x under C2,
`-XX:-UseJVMCICompiler`, the JIT a stock OpenJDK runs a `.class` under) against the
fused GEMV's own 1.5-2.0x, because a token is the GEMVs plus the attention, the norms,
the logit argmax and the layer walk, none of which the width touches. The load halves
on every model; that is the reader (BF16 file bits into a `#bf16` array in one
`read-sequence`), not the kernels.

One trap in the harness, since closed twice over: `-m chat` on a model whose row carries
no chat template (TinyLlama-Chat is `model_type` `llama`, and the `llama` row has none)
used not to fail -- it fed the raw prompt, the model answered EOS at once, and the printed
tok/s covered the nine prompt positions. It is a usage error now, and a run that samples
nothing prints no rate at all. TinyLlama's rows above are the raw completion for that
reason, and its earlier "chat prompt" rows on this page should be read with it in mind.

### bf16 weights on the device: `--gpu -w bf16`

`--gpu` takes `vec:matvec` over a `#bf16` weight matrix on an NVIDIA card since
2026-09-06: the kernel decodes the stored patterns in its lane loop and is
otherwise the single-float kernel, at half the bytes a resident row streams -- 2.2 ms
against 4.3 for this model's 248320x1024 head, 229 GB/s ([the guide](../../doc/en/guides/gpu-acceleration.md)).
Measured on the GB10 box (GraalVM 25, JVM class output, `-Xmx16g`, `-m chat -t 0 -n 64`
on the cat prompt from the BF16 GGUF, three runs each, the 64 tokens byte-identical
across all forty-eight). Re-measured whole on 2026-09-06 after the KV cache was bounded
to the position reached (below), the two arms interleaved run by run on one build
pair, load average under 1.8; it supersedes the table taken after the residency-guard fix
(below), which is the
"before" row here:

| Qwen3.5-0.8B | `--simd` | `--gpu --simd` | `--simd --parallel`, 16 threads | `--gpu --simd --parallel`, 16 |
| --- | --- | --- | --- | --- |
| `-w f32`, before | 6.3 / 5.9 / 5.9 | 19.4 / 19.5 / 19.4 | 17.1 / 16.9 / 17.0 | 20.6 / 20.5 / 20.2 |
| `-w f32`, after | 6.7 / 6.5 / 6.5 | 24.0 / 24.3 / 23.9 | 19.0 / 19.3 / 19.2 | 24.0 / 24.3 / 25.8 |
| `-w bf16`, before | 7.9 / 8.1 / 8.1 | 26.2 / 26.5 / 24.2 | 25.5 / 23.9 / 25.7 | 27.6 / 27.2 / 27.1 |
| `-w bf16`, after | 9.0 / 9.0 / 9.0 | 33.6 / 34.6 / 32.6 | 31.2 / 30.0 / 29.3 | 33.9 / 35.8 / 34.0 |

Three readings. **The device leg is not GEMV-bound**: bf16 leads f32 by 1.3x with the
flag, the same lever the CPU legs get, though the device streams half the bytes -- if the
GEMV were the arm, halving its bytes would show up as more than the width shows anywhere
else. Profiled a forward pass at a time (2026-09-06: `nsys` per forward, JFR
per function; the probes `decode-per-token.py` / `decode-jfr-agg.py` in
`.todo/artefacts/123-gpu-acceleration/`), a steady forward WAS **45-46 ms under the flag,
at one thread or sixteen, against 24 under `--simd --parallel` and 81 under `--simd`** --
each the difference of a 128- and a 64-token run, which drops the JIT warm-up and the
prompt. Of the 45: the device kernels are 7.5 ms
(229 launches; 6.8 of bf16 GEMV, the head 2.2 of it; 0.7 of f32 GEMV over the KV cache),
each waited for by the host form that reads its result; the driver calls on the calling
thread 11.9 ms (7.9 in 229 downloads with the kernel waits inside them, 2.4 in 193 uploads
-- 102 MB, the 24 KV-cache matrices at 4 MB each going up every token because they are
written every token and read by four heads, since closed by the KV-cache bound below -- and 1.1
in launches and allocations); and this model's Gated DeltaNet loops on the host ~30 ms, where the same
three functions cost 6.8 ms without the flag -- the residency guards the flag put in
front of every store of the 128x128 state and every element of the boxed convolution loop
were 40% of the arm's samples.

**Taking the guard off every typed store took those 23 ms out and halved the arm**
(2026-09-06, same box, same checkpoint, same method, medians of two 256-minus-64
rounds, the tokens byte-identical across every configuration measured): **50.7 / 51.8 ms a forward before, 25.0 / 25.1
after**, against 27.6 / 31.7 -> 26.2 / 27.2 for `--simd --parallel` and 90.6 -> 88.7 for
`--simd`. Two mechanisms. A typed `dotimes` called `_gpuWritten` on EVERY store and now
reports each stored array once, at loop entry (`.kb/jvm-typed-loops.md`) -- 23 of the
26 ms. And `causal-conv` / `silu-in-place` ran boxed: the first because this file was
narrowing the depthwise conv kernel, **F32 in the checkpoint**, to the `-w bf16` weight
width, and one bf16 operand puts a whole typed loop on the boxed path -- it is read
element by element and nothing accelerates it, so `as-f32-matrix` now keeps it at the
file's own width; the second because its COUNT, `(length v)`, was the one call in it, now
an admitted form. **The device arm edges past `--simd --parallel` on this model as a
result**, and a narrower weight width could still only shrink the 6.8 ms of GEMV -- 27% of
the shorter forward rather than 15% of the long one -- which is the arithmetic Q4 on the
device stays refused against (`.kb/gpu.md`, "What is deliberately NOT here").
**The rate printed for the rows above understates the forward rate**: the harness of the
day divided the 64 sampled tokens by the clock of 84 forward passes -- the 21-id chat
prompt ran through the same loop, in the clock and not in the count -- and the first ten
of them are 1.5-2x slow while the JIT warms, so 19.3 printed is 41 forwards a second
steady and 11.0 is 22. That is the bias the note at the top of this section describes and
the harness has not had since 2026-09-07; the rows here are the printed figure of the day
and compare with each other, not with a forward rate. **The two arms are now level on this box, the device one
marginally ahead** (25.8 against 25.3 printed; 25.0 against 26.7 ms a forward) -- where
before that fix `--simd --parallel` won 1.9x. Sixteen threads still buy the device arm
almost nothing (26.2 against 25.8), for the reason the stories15M table below gives: the
parallel workers and the driver compete for the cores. The width's own lever is
intact on the CPU legs (1.2x on one thread, 1.1x on sixteen), as the table above found on
the other box. **And the residency budget was never the constraint.** Under the
interceptors the library keeps results on the device and its budget is the headroom
rule -- everything the card has less an eighth -- so this 1.5 GB model sits resident from
its second token; the 1 GB eager cap applies only to an
embedder that leaves lazy results off. Forced below the model through the package-private
seam (`.todo/artefacts/123-gpu-acceleration/ResidencyCliff.java`): a 512 MB budget --
above the largest matrix, below the model -- decodes at **6.7-6.8 tok/s, BELOW `--simd`'s
7.7**, and 256 MB or 64 MB at 5.8-5.9, because every evicted matrix is a first sight again
on its next token (declined to the CPU) and an upload on the one after, so the loop
alternates between the CPU rate and a cold trip. **A run in that state now says so**: one
line on standard error when it ends, naming the budget, what went up again after being
evicted and the hit and miss counts, on the interpreter and the compiled class alike
(`.kb/gpu.md`, "A budget below the working set"). Nothing is printed while the budget
holds, which on this box is every run.

**The KV cache holds the positions the run has REACHED, and that is the
"after" row of the table above** (2026-09-06, same box, same checkpoint, same method).
`attention` scored the WHOLE cache every token -- 4096 rows of keys and 4096 columns of
values a head, when `pos` of them are non-zero and the rest is arithmetic over deliberate
zeros. The matrices now start at 32 positions and DOUBLE (`grow-kv-cache`), so both GEMVs
cost O(`pos`) with the waste under 2x, and every token measured is byte-identical to what
the full-length cache answered -- at 64 tokens, at 256 and at 1024, at `-w f32` and
`-w bf16`, on the CPU arm and the device arm. Where it went, per forward pass:

| Qwen3.5-0.8B, `-w bf16` | before | after |
| --- | --- | --- |
| `--simd`, 1 thread | 90.6 / 86.3 ms | 78.4 / 75.0 ms |
| `--simd --parallel`, 16 | 26.8 / 25.9 ms | 20.5 / 22.8 ms |
| `--gpu --simd`, 1 thread | 24.3 / 26.2 ms | 18.3 / 18.6 ms |
| device: HtoD a forward | 193 copies, 102.0 MB, 2.3 ms of `cuMemcpyHtoD` | 97 copies, 0.74 MB, 0.26 ms |
| device: kernels a forward | 7.48 ms, 229 launches (0.73 of f32 GEMV over the cache, 72 of them) | 6.74 ms, 157 launches, no f32 GEMV at all |
| device: CUDA API on the calling thread | 10.55 ms | 8.15 ms |
| CPU: `matvecRowsF` share (JFR, `--simd` 1 thread) | 13.9%, 10.4 ms a forward | 2.2%, 1.4 ms |

Two readings. **The device stopped uploading the cache**: 100 of the 102 MB a forward were
the 24 cache matrices, and a bounded matrix is under the member's 2^17-element threshold,
so the attention GEMVs decline to the CPU lane kernel and cost 1.4 ms there instead of
0.73 ms of kernel plus 2.3 ms of upload plus the first sights. **And the shape was worth
more than any width**: the same edit is 1.15x on the one-thread CPU arm, 1.22x on the
parallel one and 1.36x on the device arm, where a narrower weight width could only ever
have shrunk the 6.8 ms of bf16 GEMV. The device arm's lead over `--simd --parallel` grows
with it (18.5 against 21.6 ms a forward; 33.6 against 31.2 printed), which supersedes the
"the two arms are now level" reading above it.

The route NOT taken, though it was the first one proposed: a row-count argument on `vec:matvec`
itself. The value cache is TRANSPOSED, so its bound is a column bound and not a row bound
-- one member would have needed two kinds of bound, on four backends times `--simd`,
`--blas`, `--gpu`, `--parallel`, bf16 and Q8_0, plus a reference page in two languages --
for a caller that can express the same bound by allocating what it uses. The library
surface is unchanged; `vec.lisp` and `.kb/vec.md` do not mention this at all.

**What a narrower weight width would now buy the device arm, measured** (2026-09-07,
same box and method, two rounds): the forward is 16.6-16.9 ms at `-w bf16`
and 22.0-23.7 at `-w f32` -- the same 157 launches at twice the bytes cost 5.1-7.1 ms,
against a kernel difference of 5.5-6.3 -- so the arm is linear in the GEMV kernel time,
and a Q4_0 GEMV kernel measured at this model's shapes would take 4.5-5.1 ms off it
(a ~12 ms forward, 1.4x), a Q8_0 one 2.7-3.4 (~13.5 ms, 1.2x). Q8_0 on the device came
next (below) -- the width the publisher's `Q8_0` file already loads at, whose GEMVs
`--gpu` then declined to the CPU one and all; Q4_0 stays refused behind it, with the
arithmetic in `.kb/gpu.md`, "What is deliberately NOT here".

### Q8_0 weights on the device: `--gpu` over the Q8_0 GGUF

`--gpu` takes `vec:matvec` over a `rontolisp:quantized-matrix` on an NVIDIA card since
2026-09-07: the kernel streams ggml's 34-byte blocks as the file holds them,
with the activation quantized on the host by the CPU kernel's own rule, and -- unlike the
f32 and bf16 kernels, which land on the portable definition's bits in practice -- it IS
those bits, on every row, because the width's CPU contract is bit-for-bit and the device
kernel keeps to it (`.kb/quantized-matrix.md`). Measured on the GB10 box (GraalVM 25,
JVM class output, `-Xmx16g`, `-m chat -t 0 -n 64`, the cat prompt, ggml-org's
`Qwen3.5-0.8B-Q8_0.gguf` against its `-BF16.gguf` at `-w bf16` -- the flag is on the
command line for both and a quantized matrix ignores it; three runs each, the two files
interleaved; **the first table on this page printed by the 2026-09-07 harness**, so its
figures are generated tokens over the forwards that generated them, the average then the
second half in parentheses, and do not compare with the older tables above):

| Qwen3.5-0.8B, `-n 64` | `--simd` | `--gpu --simd` | `--simd --parallel`, 16 threads | `--gpu --simd --parallel`, 16 |
| --- | --- | --- | --- | --- |
| `Q8_0` file | 14.0 (14.0) / 14.1 (14.2) / 13.8 (13.9) | 61.0 (63.8) / 56.4 (60.1) / 59.0 (60.8) | 58.8 (60.8) / 52.8 (53.4) / 53.2 (53.9) | 56.3 (57.9) / 57.2 (58.7) / 57.1 (58.7) |
| `BF16` file, `-w bf16` | 13.3 (13.4) / 13.3 (13.3) / 13.2 (13.2) | 57.5 (59.3) / 57.9 (59.1) / 54.1 (53.8) | 45.6 (46.4) / 45.8 (46.4) / 45.8 (46.2) | 58.1 (60.4) / 56.9 (58.7) / 55.1 (59.0) |

**The forward.** Steady, at `-n 256` on `--gpu --simd`, one thread, two rounds: the
second half's rate is **64.9 / 64.9 tok/s over the Q8_0 file against 57.1 / 56.1 over the
BF16 one -- 15.4 ms a forward against 17.5-17.8, 1.14-1.16x**. By the 256-minus-64 method
above, which subtracts everything a run pays once (the load, the JIT's warm-up, the
prompt, the context creation and the weight upload), the increment a forward is **13.9 /
13.9 ms against 16.7 / 16.7 -- 1.20x, 2.75 ms, what the measurement above predicted for the width
(2.7-3.4 ms)** -- and the device-side GEMV a forward is 5.3 ms against bf16's 7.7
(`.kb/gpu.md`; the 270 MB head at 190 GB/s). The Q8_0 file loads in 1.3 s against 2.1 (0.83
GB of blocks read into place). On the CPU arms the width is level with bf16 on one thread
(14.0 against 13.3) and 1.15-1.3x on sixteen (53-61 against 46), where the quarter-size
bytes pay (`.kb/quantized-matrix.md`, "What it costs"). **The tokens**: the Q8_0 file's 64
and 256 positions are byte-identical between `--gpu --simd` and `--simd`, on one thread and
sixteen, and round to round -- the flag changes no bit at this width -- and the Q8_0 and
BF16 files part at position 40 (two widths, as on the raw
completion above).

**What the run found in this file** (2026-09-07): `split-gated-q`, which splits Qwen3.5's
`attn_q` (`query | gate` per head) into `:wq` and `:gate`, rebuilt the halves with
`make-array` at the SOURCE's element type -- for a quantized source that is a general
array, whose `vec:matvec` is the boxed defun -- so every `wq` and `gate` GEMV of the Q8_0
file ran on the defun on both arms: 5.6 tok/s at `--simd` and 2-4 under the flag, where
the residency guard on every boxed element read made the device arm the SLOWER one. The
Q8_0 halves are now GATHERED block for block (`split-gated-q-blocks`: a row is whole blocks,
so each half is one `rontolisp:quantized-rows` over the source rows its heads name), rather
than through `rontolisp:dequantize` / `quantize` because this program also compiles to WASM,
where those two names are refused at compile time. The
`--simd` column above is the CPU arm after that fix (9.7 printed by the old harness, against
5.6 before it).

## The layer table

The one thing here that is not `run.c`: the forward pass is a **table of layer
kinds**, not Llama 2 spelled out. A model is a list of layers, every one of them
the same residual sandwich

```
x <- x + residual-multiplier * f(rmsnorm(x, the layer's norm), the layer)
```

differing only in what `f` is -- the layer's `:kind` -- and in the options
recorded beside it when the model was loaded:

| option | what varies | who has it |
| --- | --- | --- |
| `:q-norm` / `:k-norm` | RMSNorm over each head's own dims of q and k | Qwen3, LFM2.5 |
| `:rope` | `:pairs` (adjacent pairs, what llama2.c's `.bin` and a llama.cpp-converted GGUF of a Llama-family model hold -- the converter permutes Q and K only for those), `:halves` (Hugging Face's `rotate_half`, what a safetensors file and a Qwen GGUF hold), or `nil` -- no rotation at all | SmolLM3 leaves every 4th block unrotated |
| `:rotary-dim` | how many of each head's dims rotate | partial-RoPE models (Qwen3.5: 64 of 256) |
| `:scale` | the attention scale, when it is not `1/sqrt(head-size)` | Granite |
| `:gate` | an output gate over the head outputs, before `wo` | gated attention (Qwen3.5) |
| `:full-attention-interval` | a hybrid: every Nth block is `:attention`, the rest the row's `:mixer` | Qwen3.5 (4) |
| `:layer-types` | the same thing given as an explicit per-block list, which WINS over the interval -- what a reader builds from `layer_types` or from a GGUF's per-layer KV-head array | LFM2.5, whose pattern is irregular |
| model-wide | `:rope-theta`, `:eps`, and the embedding / residual / logit multipliers | Granite, and everything since Llama 2 moved `rope_theta` |

`*architectures*` is the other half: one row per `general.architecture` (GGUF) /
`model_type` (`config.json`), holding what that family does differently. A
reader of a published checkpoint looks its file's name up, prepends what the
FILE decides (the RoPE layout, and any per-checkpoint scalar), and hands the
result to `transformer-layers`, which builds the list the forward pass walks.

llama2.c's `.bin` is the row where every option is at its default -- `llama`,
`:rope :pairs` -- so the table degenerates to `:attention` then `:swiglu` per
block, which is Llama 2, and the stories stay byte-identical.

### The Gated DeltaNet layer (Qwen3.5)

Qwen3.5 (and 3.6 / 3.8, the same `qwen35` architecture) puts a gated linear
recurrence in three of every four blocks: per head a 128 x 128 state matrix
`S` that decays a little each token, is corrected toward the current value
along the current key (the delta rule) and is read out along the query --
no KV cache, the state is the whole memory. It is the third `:kind`,
`:deltanet`, and lives in [`deltanet.lisp`](deltanet.lisp): the single-token
path of `transformers`' `modeling_qwen3_5.py` (`causal_conv1d_update`,
`torch_recurrent_gated_delta_rule`, `Qwen3_5RMSNormGated`), with the weights
plist a checkpoint reader hands it documented in the file's header. `S` is
kept transposed so both reads (`k^T S`, `q^T S`) are a `vec:matvec`; the decay
and the rank-1 update are one typed `dotimes` over the state, which the JVM
backend compiles to a primitive loop.

[`deltanet-check.lisp`](deltanet-check.lisp) pins the arithmetic:
[`deltanet-ref.py`](deltanet-ref.py) is the PyTorch reference transcribed
into plain float64 Python over pseudo-random inputs, and the check prints the
same numbers -- the recurrence alone at heads 2 / dim 4 over three tokens
with the final states, then the whole decode step over five tokens of a
dim-8, two-head, kernel-4 layer -- at 3 decimals, none within 2e-5 of a
rounding boundary, identically on the interpreter, the JVM, wasm-GC and the
component, with and without `--simd`.

Where a Qwen3.5-0.8B token's time would go, measured at the real shape with
random weights (18 layers of dim 1024, 16 heads of 128, kernel 4; JVM class
output under `--simd`, ONE thread, f32 weights; commit `594ddac9`, Graal JIT
-- `UseJVMCICompiler` on -- on JDK 25.0.4, a Xeon E5-2697A v4; the numbers
move with the four-accumulator GEMV row (`.kb/vec.md`), whose column gate sits
exactly at this 128 x 128 GEMV shape):

| per token | ms |
| --- | --- |
| the 18 Gated DeltaNet mixers, whole | 103 |
| of which the recurrence, 18 x 16 heads (two 128 x 128 `vec:matvec` + the fused decay / rank-1 update over the 128 x 128 state) | 21 |
| of which the causal convolution (6144 channels x 4 taps) | 1.9 |
| of which the projections (f32 GEMVs, 756 MB per token) | 62 |
| for scale: one block's SwiGLU GEMVs x 24, cache-warm | 82 |
| for scale: the tied 248320 x 1024 classifier, f32 | 110 |

So the rank-1 update as a typed loop is about 4 ns per state element and
under a tenth of a token; a `vec:ger-into` kernel is not worth its surface
until the GEMVs shrink under it (bf16 weights halve the GEMV rows above).

### The short-conv layer (LFM2)

LFM2 / LFM2.5 (`lfm2`) put the simplest hybrid layer in the field in ten of
sixteen blocks: `in_proj` splits the normed input into `B | C | x`, `B * x`
goes through a causal depthwise convolution of kernel 3 -- no activation, no
matrix state, the previous two inputs are the whole state -- and `C` gates the
result before `out_proj`. It is the fourth `:kind`, `:shortconv`, in
[`shortconv.lisp`](shortconv.lisp), over the same one-token convolution step
the Gated DeltaNet layer runs ([`causal-conv.lisp`](causal-conv.lisp), loaded
once through `require`). LFM2's pattern of conv and attention blocks is
irregular, so its readers pass the explicit `:layer-types` list rather than an
interval. [`shortconv-check.lisp`](shortconv-check.lisp) pins the step against
[`shortconv-ref.py`](shortconv-ref.py) the way the DeltaNet check does, four
tokens of a dim-8 kernel-3 layer so the window fills and wraps.

At the 1.2B shape (ten layers of dim 2048, kernel 3; JVM class output under
`--simd`, one thread, f32 weights, random; commit `b757d1a8`, Graal JIT on JDK
25.0.4, a Xeon E5-2697A v4):

| per token | ms |
| --- | --- |
| the 10 short-conv mixers, whole | 69 |
| of which the convolution (2048 channels x 3 taps) | 0.2 |
| of which the two projections (6144 x 2048 and 2048 x 2048, f32, 671 MB per token) | 68 |
| for scale: one block's 8192-wide SwiGLU GEMVs x 16, cache-warm | 333 |

The conv is noise; the layer is its two GEMVs, and the model is its SwiGLU
(3.2 GB of f32 per token here, which is why this rung is where the bf16 and
Q8_0 weight widths matter).

## Why `--simd` (and `--parallel`, and `--gpu`)

Decoding is one token at a time, so every matrix in the model multiplies a
vector: the whole forward pass is GEMV (`vec:matvec`), 15 million multiply-adds
per token for stories15M, and `--simd` lowers it to CPU vector instructions;
`--simd --parallel` splits each GEMV's rows across the cores, and `--gpu` moves
the big ones to the device. Measured on this project's NVIDIA GB10 box (aarch64,
10 Cortex-X925 at 3.9 GHz + 10 Cortex-A725, GraalVM 25), the 222-token story
above, every row re-measured together on 2026-08-22 -- medians of three
interleaved runs, nothing pinned:

| backend | threads | scalar | `--simd` | `--simd --parallel` | `--gpu --simd` | `--gpu --simd --parallel` |
| --- | --- | --- | --- | --- | --- | --- |
| JVM | 1, or 20 under `--parallel` (the default at the time; it is 10 now) | 104 tok/s | 336 tok/s | 637 tok/s (684 with `RONTOLISP_THREADS=10`) | 458 tok/s | 427 tok/s |
| wasm-GC (`wasmtime`) | 1 | 0.4 tok/s | 125 tok/s | -- (no threads) | -- (no FFM) | -- |
| interpreter (`java -jar`) | 1, or 20 under `--parallel` | ~15 s per token | 44 tok/s | 44 tok/s | 42 tok/s | -- |

The two JVM `--gpu` cells were **re-measured on 2026-09-06 after the residency-guard fix** (which
stopped a typed `dotimes` reporting a residency guard per store) and rose about 1.2x, the
story byte-identical: `--gpu --simd` 449 -> 555 tok/s and `--gpu --simd --parallel`
425 -> 551, medians of five and three interleaved runs against the same build. The other
columns did not move (`--simd --parallel` 657 before and 654 after, same rounds), so the
recommendation below is unchanged; the rest of the table is still 2026-08-22's.

The four accelerated JVM cells moved again the same day, when the KV cache was
bounded to the position reached (see the Qwen section above) -- before and after taken
together, medians of three interleaved runs on one build pair, all eight stories
byte-identical: `--simd` 346 -> 348 tok/s, `--simd --parallel` at `RONTOLISP_THREADS=20`
611 -> 660, `--gpu --simd` 508 -> 524, `--gpu --simd --parallel` 481 -> 515. **This model
is where the bound buys the least**: its window is 256 positions and the story is 222 of
them, so the cache is near full for most of the run -- which is exactly why the same edit
is 1.15-1.36x on Qwen3.5's 4096-position window. The recommendation is unchanged.

Without `--parallel` every rontolisp backend decodes on ONE thread (the JVM
still runs its own GC and JIT threads: ~3.1 s of CPU for a 1.4 s run); with it
the GEMVs run on every core and the rest of the token still runs on one. The C
and Java ports of the same program, same box, same story, are the reference:

| reference | threads | tok/s |
| --- | --- | --- |
| `run.c -O2` | 1 | 147 tok/s |
| Java Vector API port of run.c ([kishida's gist](https://gist.github.com/kishida/05656bfcbe840f269784f7dbbee5928e), `-v on`), every `.parallel()` removed | 1 | 312 tok/s |
| the same gist as published, `matmul` and the attention heads being `IntStream.range(...).parallel()` | 20 | 513 tok/s |

So the standing today, stated plainly: **on one thread `--simd` (336) beats
that port (312), and on 20 threads `--simd --parallel` (637) beats the gist as
published (513)**, the same thread count on each row. Before the JVM backend's
typed loops (also 2026-08-22) both rows lost (221 against 297, 319 against 535,
measured the same way): the GEMVs were already at
parity, and the ~2 ms a token this file spent in boxed Lisp around them -- the
softmax, RoPE, attention copies and KV-cache loops, ~60 ns an iteration of
`Double`/`Long` allocation and `Object` dispatch -- was the whole gap. The JVM
backend now compiles a `dotimes` of that shape to a primitive loop
(`.kb/jvm-typed-loops.md`; the same values, ~30x on the softmax), and the GEMV
kernel vectorizes a short row (the 48-wide attention head used to run scalar,
`.kb/vec.md`); nothing in this file changed. `--gpu --simd` (555 since the residency-guard fix) and
`--gpu --simd --parallel` (551) both still trail `--simd --parallel` (654): the
device takes the big GEMVs but pays a synchronous download per call, and with the
spinning worker threads also competing with its driver for the cores the
combination is no faster than the device alone -- pick `--simd --parallel` for
this program. (The bigger Qwen3.5-0.8B above goes the other way, its device arm
edging ahead: that model's per-token GEMV bytes are 25x this one's.) `--blas` entered the intercepted set on 2026-09-02: `vec:matvec` and
`vec:matvec-into` are a `cblas_?gemv`, so the flag now reaches this program
(`doc/en/guides/blas-acceleration.md`). It is NOT in the table above because it
was not measured on this box -- on a 64-core Xeon E5-2697A v4 with OpenBLAS the
JVM backend decodes stories15M at 102-110 tok/s under `--simd` and 121-124 under
`--simd --blas` with `OPENBLAS_NUM_THREADS=1`, so about 1.15x, with the story
byte-identical at 150 tokens. **Leave the thread cap off and it is a rout**: at
the library's default thread count the same run drops to 16 tok/s, because a
GEMV is short and memory-bound and the per-call thread barrier swamps it.
Two caveats that keep the two tables honest: the gist's `-t 0` decode does NOT
reproduce run.c's story (a different one comes out, so its rows are throughput
only), while every rontolisp row is byte-identical to `./run stories15M.bin -t 0
-i "Once upon a time"`; and an earlier version of this table (JVM 23 / 87,
wasm-GC 0.4 / 46, `run.c` 65, the gist 100 / 187) was measured on 2026-08-19 on
a different, 64-core x86 box -- those numbers must not be compared with the rows
above.

The `--simd` lane kernel streams the 60 MB of weights at ~20 GB/s, about 2.4 ms
of a 3.0 ms token on one JVM thread; what is left is the attention's 72 small
GEMVs and the kernel calls between them (the next lever then: the
GEMV row was one accumulator chain, since split, `.kb/vec.md`). `--simd --parallel` runs
every GEMV above ~2^15 multiply-adds -- all of them here, the 288x288
projections included -- over a row range per thread, bit-identical to the
serial kernel ([the guide](../../doc/en/guides/simd-acceleration.md#using-more-than-one-core---parallel));
`RONTOLISP_THREADS=10` was slightly better than the 20 threads this box
defaulted to when the table was measured, because the second ten cores are the
small ones -- half the processors is what the default became on 2026-09-06
(`.kb/simd-parallel.md`), so 10 IS the default here now and that table's `--parallel`
column is the explicit-20 one. `--gpu --simd` moves the GEMVs whose matrix is big enough and
STAYS on the device -- the three feed-forward matrices per layer and the
classifier head, two thirds of the multiply-adds; the 288x288 projections are a
tie at ~12 us and stay on the CPU -- from their second token on, once the
library has seen the weight twice unwritten ([the guide](../../doc/en/guides/gpu-acceleration.md)).
That is about 1.4x over `--simd` with the story unchanged, and below
`--simd --parallel` on this box. On the interpreter neither flag buys anything (44 tok/s
under `--simd --parallel`, 42 under `--gpu --simd`): the tree walk around the
GEMVs dominates there. On an Apple M4 Max the JVM decodes the
same story at ~370 tok/s under `--simd` and at the same ~370 under `--gpu --simd`,
story unchanged: only the classifier head is above Metal's threshold there, and
the one GEMV per token it moves pays the GPU's idle-clock penalty after the
2.7 ms of CPU work between tokens ([the guide](../../doc/en/guides/gpu-acceleration.md#on-apple-silicon)). The `--simd` kernel's deliberately pinned
128-bit accumulation (one chain per row, so results agree bit for bit with the
WASM `f32x4` kernels on every host) is what the device does NOT reproduce -- it
keeps a compensated pair of single floats (a double until 2026-09-06), which carries
the bits of the scalar `vec.lisp` definition's double accumulation, and lands on that
definition's bits instead.

The interpreter's `--simd` needs the native binary or
`java --add-modules jdk.incubator.vector -jar ...`; without the Vector API it
runs the scalar `vec.lisp` kernels, one interpreted form per multiply-add,
which is fine for stories260K and not for stories15M.

The checkpoint's 15 million little-endian `float32`s load in about 0.2 s on
every backend: `read-sequence` over a packed single-float array reads raw
IEEE-754 elements in bulk, one transfer per weight matrix, so the loader is a
`make-array` and a `read-sequence` per tensor.

[`../ml/tiny-llm.lisp`](../ml/tiny-llm.lisp) is the arithmetic core of this
file with the I/O taken away, and explains the KV-cache layout (keys row-major,
values transposed) that makes both halves of attention a GEMV.
