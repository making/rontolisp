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

## Remaining

The readers it waited on are in: `.todo/674` (the tokenizer) and `.todo/673` (the GGUF
reader, plus `llama2.lisp`'s `load-gguf-checkpoint`, whose `lfm2` mapping -- the
per-layer `head_count_kv` zeros as `:shortconv`, `shortconv.*`, `token_embd_norm` -- is
written but has not run a file yet) closed 2026-09-03; `.todo/675` (the safetensors
reader, with `load-hf-checkpoint`'s `lfm2` mapping) is open only for its WASM fixture
leg. What remains is this item's own run: LFM2.5-1.2B-Instruct from its safetensors
(downloaded to dorian's scratchpad, `../lfm25/` beside the Qwen files named in
`.todo/677`) and from Liquid's BF16 GGUF, the same text from both, the llama.cpp
comparison, the tok/s rows -- the path `.todo/677` walked for Qwen3.5, with the stop
token trap it found (`tokenizer_config.json`'s `<|im_end|>`, and never a stop token that
is part of the prompt) already handled in `generate`.

Verify items 2-4 below: the real checkpoint from GGUF and from safetensors, the
llama.cpp comparison (byte-identical through Q8_0 once `.todo/672` lands), the tok/s
rows. The attention layers' 64-wide head is a reader-independent thing to check when
the model runs: whether the 64-column `vec:matvec` rows of the attention take the
lane kernel (`.todo/480`'s column gate sits above it). `generate`'s stop token
(`<|im_end|>` 7) and the `<|startoftext|>` prefix are `llama2.lisp`'s side of the
tokenizer item.

## Verify

- The conv step against a hand-computed 3-tap fixture.
- LFM2.5-1.2B from the official BF16 GGUF and from the safetensors: the same text at
  temperature 0 from both; coherent, compared by eye with `llama.cpp` on the same prompt.
- Q8_0 official GGUF through `.todo/672` when it lands: the byte-identical-to-llama.cpp
  check is available here because Liquid publishes the Q8_0 file itself.
- tok/s single-thread and `--parallel`, f32 and bf16, in the example README with the JIT
  named. This is the rung whose numbers should go in `.todo/489`'s table as "the 1B".
