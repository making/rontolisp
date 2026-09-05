# llama2.c in rontolisp

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
| -- | `LLAMA2_TRACE` | set to anything: every token id and its text on stderr |

From this directory, on all four backends. The interpreter takes the program's
own arguments after `--` (everything before it is the compiler's); a compiled
artifact takes them straight after itself:

```bash
ARGS='stories15M.bin -t 0 -i "Once upon a time"'

rontolisp llama2.lisp --simd -- $ARGS                            # interpreter
rontolisp llama2.lisp -o Prog.class --simd && \
  java --add-modules jdk.incubator.vector Prog $ARGS
rontolisp llama2.lisp -o Prog.class --gpu --simd && \
  java --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector Prog $ARGS  # + an NVIDIA GPU
rontolisp llama2.lisp -o llama2.wasm --simd && \
  wasmtime run --dir . llama2.wasm $ARGS
rontolisp llama2.lisp -o llama2.wasm --simd --component && \
  wasmtime run --dir . llama2.wasm $ARGS
```

Every one of them prints

```
Once upon a time, there was a little girl named Lily. She loved to play outside in the sunshine. One day, she saw a big, red ball in the sky. It was the sun! She thought it was so pretty.
Lily wanted to play with the ball...
```

which is what `./run stories15M.bin -t 0 -i "Once upon a time"` prints -- the
whole 256-token story is byte-identical, and so is the command line. The small
model runs the same way with `stories260K.bin -z tok512.bin`.

## A Hugging Face checkpoint

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

TinyLlama-1.1B-Chat uses the Llama 2 tokenizer -- the same 32000-entry
`tokenizer.bin` the stories do -- so it runs today, before the byte-level BPE
tokenizer item lands:

```bash
# download config.json + model.safetensors (2.2 GB) into a directory, then
rontolisp llama2.lisp --simd -- TinyLlama-1.1B-Chat-v1.0 -z tokenizer.bin -t 0 -n 40 -i "Once upon a time"
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
Halving the bytes (bf16 weights, `.todo/484` / `.todo/487`) is the lever, not
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
rontolisp llama2.lisp -o Llama.class --class-name Llama --simd
java --add-modules jdk.incubator.vector -Xmx16g Llama Qwen3.5-0.8B -m chat -t 0 -n 64 \
  -i "Tell me a short story about a cat."
```

```
***

### Barnaby the Cat

Barnaby was a small, fluffy cat with a tail that was always a perfect ...
```

The same model as ggml-org's `Qwen3.5-0.8B-BF16.gguf` -- one file, the tokenizer
inside it, read by the shipped
[`gguf:`](../../doc/en/reference/functions/gguf.md) package -- answers the same
prompt with the same text, token for token, and needs no `tokenizer.json`
(`Llama Qwen3.5-0.8B-BF16.gguf -m chat ...`). A `Q8_0` file is refused by
name until the quantized weight matrix exists. `llama.cpp` on the same GGUF,
same prompt, thinking off, tells the same cat's story in other words -- the
same "Barnaby", a different sentence -- which is what two implementations at
temperature 0 give each other; byte identity is the Q8_0 check's job.

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
prediction for bf16 weights (`.todo/484` / `.todo/487`) is close to twice
these rows, because they halve the bytes a token streams. Two
things the real checkpoint taught that its `config.json` does not say: the
vocabulary is 248070 (`vocab_size` 248320 is the padded embedding table, so
the sampler chooses among the tokenizer's ids only), and the answer ends at
`tokenizer_config.json`'s `<|im_end|>`, not at `eos_token_id`'s
`<|endoftext|>` -- both stop generation, and neither does when it is part of
the prompt. `tokenizer.json` (13 MB) is read by a byte-level JSON reader of
this file's own, because `rontolisp:json-parse` over that text does not finish
(`.todo/690`).

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
rontolisp llama2.lisp -o Llama.class --class-name Llama --simd
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
temperature 0, the whole overlap identical -- 581 characters, about 140 tokens
-- not "the same story in
other words", which is what the Qwen3.5 row above gets and what two
implementations at temperature 0 normally give each other. Worth stating
carefully: it is one model on one prompt, so it is a fact and not a rule, and
it is not the byte-identity check `.todo/672` owes on a Q8_0 file, whose point
is the quantized kernel rather than the forward pass.

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
move with `.todo/480`, whose column gate sits exactly at this 128 x 128 GEMV
shape):

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
| JVM | 1, or 20 under `--parallel` | 104 tok/s | 336 tok/s | 637 tok/s (684 with `RONTOLISP_THREADS=10`) | 458 tok/s | 427 tok/s |
| wasm-GC (`wasmtime`) | 1 | 0.4 tok/s | 125 tok/s | -- (no threads) | -- (no FFM) | -- |
| interpreter (`java -jar`) | 1, or 20 under `--parallel` | ~15 s per token | 44 tok/s | 44 tok/s | 42 tok/s | -- |

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
`.kb/vec.md`); nothing in this file changed. `--gpu --simd` (458) and
`--gpu --simd --parallel` (427) both trail `--simd --parallel` now: the device
takes the big GEMVs but pays a synchronous download per call, and with the
spinning worker threads also competing with its driver for the cores the
combination is the slower of the two -- pick `--simd --parallel` for this
program. `--blas` entered the intercepted set on 2026-09-02: `vec:matvec` and
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
GEMVs and the kernel calls between them (`.todo/480` names the next lever: the
GEMV row is one accumulator chain). `--simd --parallel` runs
every GEMV above ~2^15 multiply-adds -- all of them here, the 288x288
projections included -- over a row range per thread, bit-identical to the
serial kernel ([the guide](../../doc/en/guides/simd-acceleration.md#using-more-than-one-core---parallel));
`RONTOLISP_THREADS=10` is slightly better than the default 20 on this box because
the second ten cores are the small ones. `--gpu --simd` moves the GEMVs whose matrix is big enough and
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
accumulates in double, like the scalar `vec.lisp` definition, and lands on that
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
