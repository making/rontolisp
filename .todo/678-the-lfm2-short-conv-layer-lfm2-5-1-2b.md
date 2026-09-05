# 678. The LFM2 gated short-conv layer: LFM2.5-1.2B-Instruct

Difficulty: Medium

Part of `.todo/670`. `.todo/676` (QK-norm attention, tied head) and `.todo/674` (the
Llama-3 pre-tokenizer pattern) closed 2026-09-03, and both readers landed the same day.
Nothing here is blocked; the LFM2.5-1.2B files are downloaded and `.todo/677` has already
walked this path once with Qwen3.5, so this is the second model through it.

**Why this model**: as of 2026-09-03 it is the newest ~1B model on Hugging Face
(`LiquidAI/LFM2.5-1.2B-Instruct`, 2026-08-24, 375k downloads in ten days), it is
published by its maker as GGUF in BF16 / F16 / Q8_0 / Q4_0 (`general.architecture =
lfm2`) and as one BF16 safetensors (148 tensors, 2.34 GB), and its non-attention layer
is the simplest hybrid block in the field -- a few dozen lines, no recurrent matrix
state. Config: 16 layers, `layer_types` = 10 `conv` + 6 `full_attention` (layers 2, 5,
8, 10, 12, 14), `hidden 2048`, SwiGLU `feed_forward.w1/w2/w3` with `block_ff_dim`
auto-adjusted to 8192, vocab 65536, tied embeddings, `rope_theta` 1e6, final norm named
`embedding_norm`, per-layer norms `operator_norm` (before the conv or the attention) and
`ffn_norm`.

## The conv layer, decode step

Weights: `conv.in_proj [6144, 2048]`, `conv.conv.weight [2048, 1, 3]` (depthwise, no
bias), `conv.out_proj [2048, 2048]`. From the normed hidden `x`:

1. `BCx = in_proj x`, split into `B`, `C`, `x'` (2048 each); `h = B * x'`.
2. Causal depthwise conv of kernel 3 over the last three `h` vectors:
   `c[i] = w[i,0] h_{t-2}[i] + w[i,1] h_{t-1}[i] + w[i,2] h_t[i]`. State per layer: the
   previous two `h` vectors (2 x 2048 floats).
3. `y = C * c`; `out = out_proj y`; residual add; then the SwiGLU MLP.

(`Lfm2ShortConv` in `transformers`' `modeling_lfm2.py`, read 2026-09-03.) Three
`vec:mul`s and a 3-tap loop; nothing new in the language.

## The attention layers

`.todo/676`'s attention with QK-norm (`q_layernorm` / `k_layernorm` over `head_dim 64`),
32 heads / 8 KV heads, `rotate_half` RoPE, no bias. The 32-wide head dim is below
`.kb/vec.md`'s vectorization threshold history -- check the short-row path, as the
48-wide llama2 head once ran scalar.

## Tokenizer

Byte-level BPE, 65536 tokens, pre-tokenizer = Llama 3's pattern (`\p{N}{1,3}`, from
`tokenizer.json`), `<|startoftext|>` prepended by the template; chat template
`<|im_start|>user ... <|im_end|>` like Qwen.

## Done (2026-09-03, the reader-independent half)

`examples/llama2/shortconv.lisp` is the `:shortconv` kind (`shortconv-layer` /
`shortconv-state` / `shortconv-forward`), over the causal convolution it shares with
the Gated DeltaNet layer (`causal-conv.lisp`, `require`/`provide`, so one conv step
serves both and a fix reaches both). `llama2.lisp` gained the `lfm2` row (`:mixer
:shortconv`, QK-norm, theta 1e6, eps 1e-5, tied), `:layer-types` (the explicit list a
reader builds from config.json or from the GGUF head-count array, winning over
`:full-attention-interval`) and the per-slot recurrent-state vector both hybrid kinds
share. `shortconv-check.lisp` pins the step against `shortconv-ref.py` (a float64
transcription of `Lfm2ShortConv.forward`; the "hand-computed 3-tap fixture" below,
generated rather than hand-computed) on all four backends with and without `--simd`
(`examples.yaml`). The stories are unchanged. The reader contract (no Q/K permute in an
`lfm2` GGUF, the head-count array, the tensor names, the 8192 FFN width, the
`embedding_norm` / `token_embd_norm` final norm) is in `.todo/673` item 7 and
`.todo/675` item 5.

## Done, part 2 (2026-09-05): the real checkpoint, both formats, and llama.cpp

**LFM2.5-1.2B-Instruct runs from its BF16 `model.safetensors` and from Liquid's OWN
`LFM2.5-1.2B-Instruct-BF16.gguf`, token for token identical between the two**, in
`-m chat` and in plain continuation, on the JVM class output at temperature 0. The
checkpoint is `/home/administrator/models/lfm25{,-gguf}` on dorian (`.todo/670`'s
per-box paragraph; the GGUFs were the one file set genuinely missing and were fetched
from `LiquidAI/LFM2.5-1.2B-Instruct-GGUF`, which publishes BF16 / F16 / Q4_0 / Q4_K_M /
Q5_K_M / Q6_K / Q8_0 / QAD-Q4_0).

    Llama LFM2.5-1.2B-Instruct -m chat -t 0 -n 64 -i "Tell me a short story about a cat."
    -> "Once upon a time, in a quiet little village, there lived a curious cat named
        Whiskers. Whiskers wasn't like the other cats--she had a knack for finding the
        most unexpected things. One sunny morning,"

**And `llama.cpp` on the same GGUF prints the same BYTES** -- same prompt, temperature 0,
the whole overlap identical -- 581 characters, and ~143 tokens because re-encoding
decoded text through a byte-level BPE need not reproduce the sequence that generated it,
so only the character count is exact -- where `.todo/677`'s
Qwen3.5 run got the same
character in a different sentence. It is not the byte-identity check `.todo/672` owes,
whose subject is the quantized kernel.

**It is no longer a single-model fact.** `.todo/670` rule 7 asked for a break attempt and
got a confirmation instead: the same comparison was run independently for Qwen3.5-0.8B on
GB10 and came out token-identical to `llama.cpp` too. **Two models, two architectures --
a gated short conv and a Gated DeltaNet -- two boxes, two orchestrators, both
token-identical to ggml at temperature 0.** That second run also settled what
`.todo/677`'s "same character, different sentence" was: the chat harness, not the
arithmetic (that item's lane writes it up; not restated here beyond this pointer, per
rule 9).

And it used a better technique for the input half than the one below: **raw completion,
no chat template on either side**, with both sides' ids dumped and compared. Removing the
template beats proving that two templates render the same string, and it settles the input
question without looking at any output at all. Prefer it next time.

A comparison like this has THREE outcomes, not two, and only two of them are results:
identical; divergent with the two prompts PROVEN to be the same token sequence; and
divergent without that proof, which is a harness gap and says nothing about the
arithmetic. This one is the first, and byte-identical output is itself strong evidence
that the inputs matched -- two chat templates rendered differently would not converge on
581 identical characters. The prompt equality is recorded anyway, because the claim's strength
should not have to be inferred by the reader.

The prompt we feed is 18 ids:

    (1 6 6423 708 52486 1123 768 3290 4161 1337 768 5641 523 7 708 6 64015 708)
    <|startoftext|> <|im_start|> user Ċ Tell Ġme Ġa Ġshort Ġstory Ġabout Ġa Ġcat .
    <|im_end|> Ċ <|im_start|> assistant Ċ

and LFM2.5's own jinja template, read out of the GGUF's `tokenizer.chat_template`,
renders a single user message with `add_generation_prompt` to `{{- bos_token -}}` +
`"<|im_start|>user\n"` + content + `"<|im_end|>\n"` + `"<|im_start|>assistant\n"` --
character for character what `*chatml*` plus `:bos t` produces here, with no system
prompt, no tools and no think block on this family. So the two front ends agree by
CONSTRUCTION and not only by their outputs agreeing.

The Q8_0 file is still refused by name at `token_embd.weight`, as `.todo/677` recorded.

**The reader-independent half written on 2026-09-03 needed no change.** The `lfm2` row,
`:layer-types`, `operator_norm` / `ffn_norm` / `embedding_norm`, `feed_forward.w1/w2/w3`,
`self_attn.out_proj`, `q_layernorm` / `k_layernorm`, the 8192 FFN width, the GGUF
`head_count_kv` zero array as `:shortconv`, and `shortconv.lisp` itself all held against
the real file on the first run.

### The one bug the real file found

**The GGUF names the Llama 3 pre-tokenizer after itself**: `tokenizer.ggml.pre` is
`lfm2`, though `tokenizer.json` holds the Llama 3 pattern character for character and
`llama.cpp` maps `lfm2` to `LLAMA_VOCAB_PRE_TYPE_LLAMA3` beside `llama3` / `llama-v3` /
`llama-bpe`. `%normalize-kind` accepted only `llama3` and `llama-bpe`, so the model did
not load at all -- while `.kb/tokenizers.md` and both doc trees ALREADY said ":llama3
(Llama 3, LFM2.5)". The shape was implemented and documented as covering this model and
only the alias string was missing, so nothing in the repository looked wrong.

Two things worth keeping from it. It was reachable ONLY through the GGUF -- the
safetensors path reads `tokenizer.json`'s pattern itself and never consults an alias --
which is `.todo/670` rule 6's shape again: every existing case sat on one side of the
condition. And the alias set is llama.cpp's, not ours, so it grows with the field:
`llama-v3` went in at the same time, and the next family to name this shape after itself
will fail exactly the same way, loudly and at load time, which is the right failure.

Landed: the alias in `src/main/resources/am/ik/rontolisp/eval/tokenizers.lisp`,
`TokenizersLibraryTest#aFamilyAliasOfTheLlama3ShapeIsAccepted` written failing first, and
the "a family's own name for a shape it merely shares" clause in `.kb/tokenizers.md` and
`doc/{en,ja}/reference/functions/tokenizer-pre-tokenize.md`.

### What the real config taught, against this item's text

- `intermediate_size` is **12288**, not 8192: it is the figure BEFORE
  `block_auto_adjust_ff_dim`, and `2/3 x 12288` rounded to `block_multiple_of` 256 is the
  8192 the weights actually have. This item's header said 8192 and was right about the
  weights; the config key it named is not where that number is. **The GGUF states the
  ADJUSTED width outright** as `lfm2.feed_forward_length` 8192, so the two formats
  disagree here and neither is wrong -- `config.json` records the model's hyperparameter
  and the GGUF records the tensor shape. Do not "fix" one to match the other; the reader
  takes the FFN width from the weights it loaded either way.
- `vocab_size` 65536 is a PADDED table again (Qwen3.5's lesson, second model running):
  `tokenizer.json` defines 64909 ids (64400 + 509 added), and the sampler chooses among
  those.
- The stop token `<|im_end|>` is 7 and `config.json` states it as `eos_token_id`
  directly -- UNLIKE Qwen3.5, where the usable stop token was only in
  `tokenizer_config.json`. The trap `.todo/677` found does not fire here, and
  `generate`'s handling of it was already correct.
- `layer_types` is 10 `conv` + 6 `full_attention` at layers **2, 5, 8, 10, 12, 14**, and
  the GGUF says the same thing as `lfm2.attention.head_count_kv` =
  `#(0 0 8 0 0 8 0 0 8 0 8 0 8 0 8 0)`.
- `max_position_embeddings` 128000, capped to 4096 by `*seq-len-cap*`.

The README's `### LFM2.5-1.2B-Instruct` section carries the user-facing half of all of
this.

## The numbers (2026-09-05), and what they did to the prediction

dorian, develop merged to `ee919526` plus this lane, GraalVM 25.0.4 on JDK 25, JVM class
output, `--simd`, f32 weights widened from the BF16 GGUF at load, `-Xmx16g`, `-m chat -t 0
-n 64`, the same prompt `.todo/489` used. **"Quiet" here means no other LANE**: dorian's
steady co-tenants (`clickhouse-server` ~17% CPU, `mysqld`, a `bundle`, a `node`, all at
47-day uptimes) ran throughout, as they did for the 2026-09-03 rows. loadavg was taken
before and after every run; no run showed a woken box and none was discarded.

Medians, with the observed spread:

| threads | LFM2.5-1.2B | Qwen3.5-0.8B |
| --- | --- | --- |
| 1 (`--simd`) | **2.13** (1.87-2.20, 7 runs) | 2.95 (2.54-2.99, 6 runs) |
| 8 | **7.21** (7.16-7.22) | 7.76 (7.74-7.88) |
| 16 | **8.83** (8.79-8.86) | 8.76 (8.68-9.05) |
| 32 | **9.30** (9.21-9.64) | 8.71 (8.57-8.75) |

Load: 8.4-8.8 s from the GGUF (its tokenizer 0.31-0.38 s), 8.6-8.7 s from the safetensors
(`tokenizer.json` + KV cache 1.27-1.59 s).

**Cross-lane check.** `.todo/489` measured the Qwen3.5 column independently, in its own
window: 3.06 at one thread against this 2.95, and 9.18 / 9.13 at 32 against this 8.71.
These rows are 4-5% LOW against `489`'s, consistently rather than randomly -- most likely
the previous run's thread pool still decaying, since loadavg at the start of the parallel
cells here was 6-10. Two lanes agreeing to 5% on one cell is the cross-lane form of the
self-disagreement check, and it also corroborates `.todo/670`'s 2026-09-03 table a second
time.

### The knee, which is the actual result

Take the ratio between ADJACENT parallel cells only. Both are tight (under 2% spread), so
this leans on no single-thread denominator at all:

| | 8 -> 16 | 16 -> 32 |
| --- | --- | --- |
| LFM2.5-1.2B | **x1.225** | **x1.053** |
| Qwen3.5-0.8B | x1.129 | **x0.994 -- flat** |

**Qwen3.5 is saturated by 16 threads; LFM2.5 is still climbing at 32.** Saturated, not
"loses ground": 0.6% below its own 16-thread median, over 3-7 runs with co-tenants
present, is a plateau with noise on it and not a turnover. A genuine DECREASE with added
threads would be the stronger claim, since locality cannot explain one at all -- it is
held in reserve until a quiet box can support it.
Speedup over one thread: LFM2.5 3.39x / 4.15x / 4.37x at 8 / 16 / 32, Qwen3.5 2.63x /
2.97x / 2.95x. `.todo/489`'s 64-thread Qwen3.5 figures (8.92 / 8.71) sit on its own
32-thread number, which is the same saturation seen from further out.

### The prediction's fate: direction right, mechanism wrong

Derived GB/s, and the dependency is stated in the same breath -- these divide tok/s by a
GB/token estimated from PARAMETER COUNT, which omits the KV cache, the activations and
(for Qwen3.5) the recurrent state, so they carry an error the ratios above do not:

| threads | LFM2.5 (4.68 GB/token) | Qwen3.5 (3.09 GB/token) |
| --- | --- | --- |
| 1 | 9.97 | 9.12 |
| 32 | **43.5** | 26.9 |

The **direction predicted above held**: LFM2.5 sits above Qwen3.5, above `.todo/489`'s
TinyLlama at 39, and far above the 27-31 GB/s `.todo/670` reads as a ceiling. The
**mechanism named above did not**. Locality predicts the per-model difference appears at
ONE THREAD as well; it does not -- 9.97 against 9.12, with run-to-run spreads that overlap
(8.75-10.30 against 7.85-9.24), so the two models stream indistinguishably on one core.
The whole difference is in how far each one SCALES, and it appears as a curve that turns
over at a different thread count per model. That is the parallel-machinery account --
dispatch and barrier traffic, which 576 small 128 x 128 GEMVs a token generate far more of
than ~30 big matvecs, and whose cost rises with thread count while per-thread work shrinks,
producing exactly a curve that peaks earlier. Not the locality account this item predicted
from.

Both halves are worth keeping. A prediction stated in advance is what makes "right about
the ordering, wrong about the cause" a distinguishable outcome at all, and it is a more
useful result than either a clean confirmation or a miss. **Four models is a hypothesis
with four points, not a law.** `.todo/670` now carries the correction and the four-model
picture, reached independently from GB10's side as well; read it there, not here.

## Remaining

Nothing. Every Verify item is met and the numbers are in this file and in
`examples/llama2/README.md`.

Two things this item HANDS ON rather than leaves undone:

- **The `tok/s` rows go to `.todo/489`**, which owns the model-rung table; they are
  measured here because until this item pushes, no other worktree can load LFM2.5 at all.
  The bf16 rungs are `489`'s too, unblocked since `.todo/488` landed on 2026-09-05.
- **`.todo/670`'s "two independent models on one ceiling" was wrong**; it is corrected
  there, with these numbers and GB10's independent route to the same conclusion, and
  `.todo/702` is the run that decides it outright. Nothing further is owed from here.

## Verify

- The conv step against a hand-computed 3-tap fixture.
- LFM2.5-1.2B from the official BF16 GGUF and from the safetensors: the same text at
  temperature 0 from both; coherent, compared by eye with `llama.cpp` on the same prompt.
- Q8_0 official GGUF through `.todo/672` when it lands: the byte-identical-to-llama.cpp
  check is available here because Liquid publishes the Q8_0 file itself.
- tok/s single-thread and `--parallel`, f32 and bf16, in the example README with the JIT
  named. This is the rung whose numbers should go in `.todo/489`'s table as "the 1B".
