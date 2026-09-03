# 676. The forward pass as a table of layer kinds: Qwen3, SmolLM3, Granite

Difficulty: Medium

Part of `.todo/670`. Depends on `.todo/673` / `.todo/675` (the weights) and `.todo/674`
(every model here is byte-level BPE). Prerequisite for `.todo/677` and `.todo/678`, which
add two more layer kinds to the table this item creates.

`examples/llama2/llama2.lisp` is Llama 2 exactly: every layer is RMSNorm -> GQA attention
with interleaved-pair RoPE -> RMSNorm -> SwiGLU. The models published in 2025-2026 are
that skeleton with per-layer and per-model deltas, and the way to run them without a
framework is to make the layer a **kind with options**, in one file, and the model a
list of layer kinds read off the checkpoint's config. Surveyed 2026-09-03 (config.json
and safetensors headers over HTTP):

| model | date | params, bf16 | delta from Llama 2 |
| --- | --- | --- | --- |
| `Qwen/Qwen3-0.6B`, `-1.7B` | 2025-04 | 0.6B 1.5 GB / 1.7B | **QK-norm** (RMSNorm over each head's 128 dims, `q_norm` / `k_norm`), `rope_theta` 1e6, tied head, vocab 151936, GQA 16/8 |
| `HuggingFaceTB/SmolLM3-3B` | 2025-07 | 3B, 6 GB, two shards | **NoPE**: every 4th layer has no RoPE (`no_rope_layers`), `rope_theta` 5e6, tied head, vocab 128256, GQA 16/4 |
| `ibm-granite/granite-4.2-3b` | 2026-09 | 3B | scalar multipliers: `embedding_multiplier`, `attention_multiplier` (replaces 1/sqrt(d)), `residual_multiplier`, `logits_scaling`; `rope_theta` 1e7; vocab 100352 |
| `Qwen/Qwen3.5-*` (0.8B..9B), `Qwen3.8-27B` | 2026-06 / 08 | 0.8B 1.5 GB | everything above PLUS gated attention and 3 of 4 layers **Gated DeltaNet** -- `.todo/677` |
| `LiquidAI/LFM2.5-1.2B-Instruct` | 2026-08 | 1.2B 2.34 GB | QK-norm attention on 6 layers, **gated short conv** on 10 -- `.todo/678` |
| `google/gemma-4-E2B` / `E4B` | 2026-07 | gated licence | sliding + full attention, KV sharing across 20 layers, logit softcap, per-layer embeddings; not a target until asked for |

Qwen3.6 and Qwen3.8 publish no model under 27B; the 27B is the Qwen3.5 architecture, so
`.todo/677` covers the whole 3.5-3.8 dense line and 27B (54 GB bf16) is a matter of RAM
and `--gpu`, not of code.

## Do

1. The attention layer takes options: `qk-norm` weights or nil; `rope` `:pairs` (llama2.c's
   adjacent-pair rotation, what the `.bin` and a GGUF converted by llama.cpp -- which
   permutes Q/K for it -- use) or `:halves` (HF's `rotate_half`, what a safetensors
   holds) or `nil` (NoPE); a `rotary-dim` smaller than `head-dim` (Qwen3.5's partial
   factor 0.25, `.todo/677`); an output `gate` (`.todo/677`); a `scale` other than
   1/sqrt(d) (Granite). Same KV-cache layout as today (keys row-major, values
   transposed), so both halves of attention stay `vec:matvec`.
2. Model-level options: tied classifier (`lm_head` absent -> the embedding, transposed
   GEMV as `wcls` already is when `shared`), `rope_theta`, embedding / residual / logit
   multipliers, final norm name.
3. The loader (`.todo/673` / `.todo/675`) builds the layer list from `model_type` /
   `general.architecture`: `llama`, `qwen3`, `smollm3`, `granite`, later `qwen35`,
   `lfm2`. One table, name -> (tensor-name map, layer kinds, options).
4. Chat template: an instruct model's prompt is a fixed string per family
   (`<|im_start|>user ... <|im_end|>`), spelled out in the example, not parsed from the
   Jinja in `tokenizer_config.json`.
5. Keep it one file. `llama2.lisp` becomes `llm.lisp` (or stays and grows; decide), the
   `.bin` loader stays as the fastest path to a byte-identical story test.

## Verify

- `stories15M` still tells the identical story (the table degenerates to Llama 2).
- Qwen3-0.6B from its Q8_0 GGUF (official, `Qwen/Qwen3-0.6B-GGUF`) and from
  `model.safetensors` (BF16, 311 tensors, 1.50 GB): the same text at temperature 0 from
  both loaders, coherent, checked by eye against `llama.cpp` on the same prompt.
- SmolLM3-3B: coherent; the NoPE layers wrong shows up as garbage within a sentence.
