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
the whole 155-token overlap identical, where `.todo/677`'s Qwen3.5 run got the same
character in a different sentence. Stated as a FACT and not a rule (`.todo/670` rule 7):
one model, one prompt, one lane, and nobody has tried to break it. It is also NOT the
byte-identity check `.todo/672` owes, whose subject is the quantized kernel.

A comparison like this has THREE outcomes, not two, and only two of them are results:
identical; divergent with the two prompts PROVEN to be the same token sequence; and
divergent without that proof, which is a harness gap and says nothing about the
arithmetic. This one is the first, and byte-identical output is itself strong evidence
that the inputs matched -- two chat templates rendered differently would not converge on
155 identical tokens. The prompt equality is recorded anyway, because the claim's strength
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

## Remaining

Everything except the timing rows is done (above). What is left, sorted the way
`.todo/670` rule 3 asks:

**Blocked (on the box, not on code).** The `tok/s` rows -- single thread and `--parallel`
with `RONTOLISP_THREADS` recorded, f32, into the README and `.todo/489` as "the 1B". Two
lanes measuring `--parallel` on dorian at once destroy each other's numbers
(`.todo/697`), so this waits for an exclusive window. The bf16 rows are unblocked as of
2026-09-05 (`.todo/488` landed) but belong to `.todo/489`, which owns that table.

**Carried out of this item, to measure in the same window.** `.todo/670`'s "two
independent models on one ceiling" (27.0 and 30.7 GB/s) may not be a box ceiling at all.

The claim is about ACCESS SHAPE, and it is stated before the measurement so that the
measurement can refute it. A token of LFM2.5 is three big matvecs per conv block (6144 x
2048 and 2048 x 2048) over ten blocks, plus 8192-wide SwiGLU; a token of Qwen3.5-0.8B is
576 small 128 x 128 GEMVs -- 18 Gated DeltaNet layers x 16 heads x 2 -- with a 1 MB state
per layer between them. Same bytes streamed buys different locality, so **the prediction
is that LFM2.5 achieves a higher GB/s than Qwen3.5 on the same box**, and that 27-31 GB/s
is a per-model figure rather than a wall. If that holds, "the parallel leg is DRAM-bound,
not thread-bound" stays true while the single number under it does not -- and that number
is what `.todo/489`'s parallel prediction rests on.

Contended runs on 2026-09-05 pointed this way, and that is ALL they are: a hint that
suggested the question. They are not evidence and are not quoted. Two models timed back to
back are not timed together -- a co-tenant arriving between the arms hits one and not the
other -- so a sequential ratio from a contended window can be manufactured, unlike a
within-run ratio, which cannot. (`.todo/488`'s README is the standing caution for the
parallel column generally: one run per cell disagreed with its own twin by 40% on a QUIET
box, from the scheduler alone. So every parallel figure here is a spread over several
runs, and the first check is whether a model disagrees with ITSELF.)

Only this worktree can load LFM2.5 until this item pushes, so this measurement cannot be
`.todo/489`'s until then, however much `489` owns the table.

**Done, not deferred.** The 64-wide attention head takes the lane kernel: the gate is
`MATVEC_ACC_THRESHOLD` = 32 COLUMNS (`.kb/vec.md`), and LFM2.5's head dim is 64 =
2048 / 32 heads. This item's header said "the 32-wide head dim", which read the head
COUNT as the head dim; there is no short-row problem here to check.

## Verify

- The conv step against a hand-computed 3-tap fixture.
- LFM2.5-1.2B from the official BF16 GGUF and from the safetensors: the same text at
  temperature 0 from both; coherent, compared by eye with `llama.cpp` on the same prompt.
- Q8_0 official GGUF through `.todo/672` when it lands: the byte-identical-to-llama.cpp
  check is available here because Liquid publishes the Q8_0 file itself.
- tok/s single-thread and `--parallel`, f32 and bf16, in the example README with the JIT
  named. This is the rung whose numbers should go in `.todo/489`'s table as "the 1B".
