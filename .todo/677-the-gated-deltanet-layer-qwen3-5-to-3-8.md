# 677. The Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model

Difficulty: High

Part of `.todo/670`. `.todo/676` (the layer-kind table, QK-norm, partial RoPE) and
`.todo/674` (the Qwen3.5 pre-tokenizer) closed 2026-09-03; `.todo/675` landed the
safetensors reader and `.todo/673` the GGUF one. Nothing here waits on a reader any
more. What remains waiting is the bf16 `tok/s` row, which needs `.todo/485` and
`.todo/488`'s wiring -- see `.todo/489`.

**Why this model**: the user wants the newest small models, and as of 2026-09-03 the
newest Qwen with a small member is Qwen3.5 (2026-06: 0.8B / 2B / 4B / 9B dense; 3.6 and
3.8 ship only 27B+, and 3.8-27B has this exact architecture, `model_type
qwen3_5_text`). Qwen3.5-0.8B is 1.75 GB of BF16 safetensors of which the language model
is 1.50 GB (the 0.20 GB vision tower and the `mtp.*` speculative head are skipped by
prefix); ggml-org publishes it as GGUF in BF16 / Q8_0 / Q4_0 (`general.architecture =
qwen35`), unsloth in every K-quant. Config (text_config): 24 layers, `hidden 1024`,
`intermediate 3584`, vocab 248320, tied head, `full_attention_interval 4` -- layers 3, 7,
11, ... are full attention, the other 18 are Gated DeltaNet.

## The full-attention layers (6 of 24)

Qwen3 attention (`.todo/676`) with three more options: `head_dim 256`, GQA 8/2;
**gated output** -- `q_proj` is `[4096, 1024]`, i.e. each head's 256 query dims are
followed by 256 gate dims (`torch.chunk(..., 2)` per head), and the attention output is
multiplied by `sigmoid(gate)` before `o_proj`; **partial RoPE** -- `partial_rotary_factor
0.25`, so only the first 64 of 256 dims rotate, `rope_theta 1e7`, `rotate_half` layout.
The "interleaved MRoPE" with sections `[11, 11, 10]` is the vision model's; for text
every position triple is the same number and it reduces to plain 1-D RoPE over the 32
frequencies.

## The Gated DeltaNet layers (18 of 24), decode step

Weights per layer (HF names; GGUF in parentheses): `in_proj_qkv [6144, 1024]`
(`attn_qkv`), `in_proj_z [2048, 1024]` (`attn_gate`), `in_proj_b [16, 1024]`
(`ssm_beta`), `in_proj_a [16, 1024]` (`ssm_alpha`), `conv1d.weight [6144, 1, 4]`
(`ssm_conv1d`, F32), `A_log [16]` (`ssm_a`), `dt_bias [16]` (`ssm_dt.bias`), `norm.weight
[128]` (`ssm_norm`), `out_proj [1024, 2048]` (`ssm_out`). 16 key heads and 16 value heads
of dim 128 (`linear_num_key_heads` = `linear_num_value_heads` = 16, so no
`repeat_interleave`). Per token, from the normed hidden `x` (1024):

1. `qkv = in_proj_qkv x` (6144 = q 2048 | k 2048 | v 2048); `z = in_proj_z x` (2048);
   `b = in_proj_b x`, `a = in_proj_a x` (16 each).
2. **Causal depthwise conv, kernel 4**, over the last 4 `qkv` vectors (state: the previous
   3), then SiLU. State per layer: 3 x 6144 floats.
3. Split into heads; **L2-normalize `q` and `k` per head** (eps 1e-6); `q /= sqrt(128)`.
   `beta = sigmoid(b)` and `g = -exp(A_log) * softplus(a + dt_bias)`, per head;
   `decay = exp(g)`.
4. Per head, with state `S` (128 x 128, f32, zero at start):
   `S *= decay`; `kv = k^T S` (a GEMV); `delta = (v - kv) * beta`; `S += k (x) delta`
   (a rank-1 update); `o = q^T S` (a GEMV).
5. Gated RMSNorm per head: `o = RMSNorm(o) * norm.weight * silu(z_head)` -- **norm first,
   then the gate** (`Qwen3_5RMSNormGated`, "Norm before gate").
6. `out_proj` over the 16 x 128 concatenation; residual add; then the ordinary SwiGLU MLP.

This is the `torch_recurrent_gated_delta_rule` of `transformers`' `modeling_qwen3_5.py`
(read 2026-09-03), the single-token path -- the chunked prefill kernel is NOT needed:
prefill is the same recurrence run token by token, which is what a decode-only engine
does anyway and what makes the bit-comparison against HF's own recurrent path possible.

## What it needs from the language

- Per token: 18 layers x 16 heads x (2 GEMVs over 128 x 128 + a rank-1 update) = 576
  small `vec:matvec` calls and 288 rank-1 updates, plus the projections. The rank-1
  update `S += k (x) delta` has no `vec:` / `linalg:` member today (`linalg:outer` exists
  in numpy's vocabulary, not here); add `vec:ger-into` (BLAS's name: `S <- S + alpha x
  y^T`, in place) as a `--simd`-intercepted kernel on the interpreter and the JVM, with
  the scalar defun as the oracle -- or write it as a typed `dotimes` and measure. State
  memory: 18 x 1 MB.
- The big GEMVs are unchanged: the tied classifier is 248320 x 1024 -- 0.5 GB of bf16
  per token, a third of the model -- so `.todo/482`'s width matters more here than for
  Llama-shaped models.
- Nothing needs a new backend feature; it is Lisp over `vec:` and typed loops.

## Done (2026-09-03, commit pending the readers)

The reader-independent half is in `examples/llama2/deltanet.lisp` (the `:deltanet`
kind: `deltanet-layer` / `deltanet-state` / `deltanet-forward`, the recurrence
`gated-delta-rule`), wired into `llama2.lisp`'s table (`qwen35` row,
`:full-attention-interval`, the `:ssm` states) and pinned by `deltanet-check.lisp`
against `deltanet-ref.py` (Verify item 1, all four backends with and without `--simd`,
in `examples.yaml`). `vec:ger-into` was NOT added: the decay and the rank-1 update are
one typed `dotimes` over the transposed state, measured at 21 ms of a ~300 ms f32 token
at the 0.8B shape (the README's table, commit `594ddac9`, Graal JIT) -- under a tenth,
so a kernel is not worth its surface until the GEMVs shrink under it. The contract the
readers must honour (the `1 + w` norms, `-exp(A_log)`, the `q_proj` interleave, the
un-permuted Qwen RoPE) is written into `.todo/673` / `.todo/675` and the two files'
headers.

## Done, part 2 (2026-09-03): the real checkpoint, from safetensors

Qwen3.5-0.8B's BF16 `model.safetensors-00001-of-00001.safetensors` (with its index,
`config.json`, `tokenizer.json`, `tokenizer_config.json`), read by `.todo/675`'s
`safetensors:read` through `llama2.lisp`'s `load-hf-checkpoint`, tokenized by
`.todo/674`'s `tokenizer:make-bpe` (kind `:qwen35`) from `tokenizer.json`, decodes
coherent text on the JVM class output at temperature 0. `-m chat` wraps the prompt in
the ChatML template with thinking off:

    Llama qwen35 -m chat -t 0 -n 64 -i "Tell me a short story about a cat."
    -> "***\n\n### Barnaby the Cat\n\nBarnaby was a small, fluffy cat with a tail that
        was always a perfect ..."

Measured on dorian (Xeon E5-2697A v4, GraalVM 25.0.4), the JVM class output, f32
weights, develop `4f43b878` + this item's lane: load 7.1-7.6 s (1.75 GB bf16 -> 3 GB
f32; tokenizer.json + KV cache 2.4-2.7 s); `--simd` one thread **2.00 / 2.48 tok/s**
(loadavg 13.2 / 21.0), `--simd --parallel` 64 threads **8.56 tok/s** (loadavg 15.1).
The parallel row is 3.2 GB x 8.56 = 27 GB/s, the box's DRAM ceiling again
(`examples/llama2/README.md`, the TinyLlama reading).

What the real config taught, against this item's text and the `qwen35` row:

- **The vocabulary is 248070, not 248320.** `vocab_size` 248320 is the padded embedding /
  classifier row count; `tokenizer.json` has 248044 entries plus 26 added tokens. The
  logits are over 248320 rows and the sampler now chooses among the first
  `tokenizer:vocabulary-size` of them (`sample-argmax` / `softmax-into-list` take `n`),
  so a padded row can never be chosen. The row keeps no vocab: it comes from the
  checkpoint and the tokenizer.
- **The stop token is `<|im_end|>` (248046), from `tokenizer_config.json`'s `eos_token`,
  not `config.json`'s `eos_token_id` 248044 (`<|endoftext|>`).** Both stop generation;
  and a stop token inside the PROMPT (the template's own `<|im_end|>`) must not -- the
  first run stopped at the prompt's `<|im_end|>` and printed nothing.
- Everything else in the row held: `full_attention_interval` 4 (and `layer_types`
  agrees), `head_dim` 256, GQA 8/2, `partial_rotary_factor` 0.25 -> 64, `rope_theta`
  1e7 (under `rope_parameters`), `rms_norm_eps` 1e-6, `tie_word_embeddings`, the `1 +
  w` norms, `attn_output_gate`, the language model under `model.language_model.` beside
  153 `model.visual.` and 15 `mtp.` tensors (skipped by prefix, 0.2 GB of I/O).
- `tokenizer.json` is 13 MB and `rontolisp:json-parse` over it does not finish
  (`.todo/690`, the measurement is there); `llama2.lisp` reads it with a byte-level JSON
  reader of its own until that lands.
- `max_position_embeddings` 262144 is capped to 4096 for the KV cache (`*seq-len-cap*`).

**From the GGUF too** (later the same day): ggml-org's `Qwen3.5-0.8B-BF16.gguf` through
`load-gguf-checkpoint` (`.todo/673` items 4-7) answers the SAME text as the safetensors,
token for token, in both modes -- the chat answer above, and `-i "Once upon a time"`
continuing "You know, in our normal world, atoms are made up" from both files -- at
2.58 / 2.92 tok/s (loadavg 1.4 / 4.8), load 7.2 s (the tokenizer comes with the file:
0.75 s). The `Q8_0` file is refused by name at `token_embd.weight` (`.todo/672`). The one
naming trap: Qwen3.5's feed-forward norm is `blk.N.post_attention_norm.weight`, not
`ffn_norm`.

**Against `llama.cpp`** (built from master in the scratchpad, `llama-cli -m
Qwen3.5-0.8B-BF16.gguf -p "Tell me a short story about a cat." --temp 0 -n 64 -st
--chat-template-kwargs '{"enable_thinking":false}'`): "In the quiet, dusty corner of
the old bakery, lived **Barnaby**, a cat with a coat of soft, burnt-orange fur and a
tail that twitched when he felt the wind. Barnaby was not a cat of the big, sleek
breeds; he was a cat of the cozy, scruffy kind" -- the same character, the same voice,
a different sentence: coherent by eye on both, not token-identical (bf16 ggml kernels
against f32 GEMVs, and its jinja rendering of the template against this file's literal
one). Byte identity is `.todo/672`'s Q8_0 check, not this one's.

Checkpoint on dorian (not in the repository):
`/tmp/claude-1000/-home-administrator-rontolisp/2657f381-d6c4-4d97-93ab-c64450bd10ac/scratchpad/qwen35/`
(`config.json`, `model.safetensors.index.json`, `model.safetensors-00001-of-00001.safetensors`
1.75 GB, `tokenizer.json`, `tokenizer_config.json`; the GGUFs in `../qwen35-gguf/`
(`Qwen3.5-0.8B-BF16.gguf` 1.56 GB, `Qwen3.5-0.8B-Q8_0.gguf` 0.83 GB); TinyLlama-1.1B-Chat
in `../tinyllama/`, LFM2.5-1.2B-Instruct in `../lfm25/`; `llama-cli` built in
`../llama.cpp/build/bin/`).

## Remaining

- The `tok/s` rows at bf16 (`.todo/485`), and at f32 on a quiet box, into the README
  and `.todo/489`.
- `.todo/678`'s LFM2.5 run through the same path (next week; its files are downloaded).

## Verify

- The recurrence against a transcription of `torch_recurrent_gated_delta_rule` with
  random small tensors (heads 2, dim 4): a pinned numeric fixture, not a torch run.
- Qwen3.5-0.8B from the ggml-org BF16 GGUF and from the safetensors: the same text at
  temperature 0 from both; coherent, compared by eye with `llama.cpp -m
  Qwen3.5-0.8B-BF16.gguf` on the same prompt (thinking mode off: `<|im_start|>assistant
  <think>\n\n</think>\n\n` is how the family's template disables it).
- tok/s single-thread and `--parallel`, at f32 and bf16, recorded in the example README
  with the JIT named. Projected from `.todo/482`'s round 2: 0.75B x 2 bytes at 26.7
  GB/s is ~55 ms of GEMV per token at bf16 on one thread.
