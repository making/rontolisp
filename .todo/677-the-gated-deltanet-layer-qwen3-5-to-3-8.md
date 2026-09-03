# 677. The Gated DeltaNet layer: Qwen3.5-0.8B, and every Qwen 3.5-3.8 dense model

Difficulty: High

Part of `.todo/670`. Depends on `.todo/676` (the layer-kind table, QK-norm, partial
RoPE), `.todo/673` / `.todo/675`, `.todo/674` (the Qwen3.5 pre-tokenizer).

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
