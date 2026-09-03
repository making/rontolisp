# 675. Read a safetensors checkpoint

Difficulty: Low

Part of `.todo/670`. `.todo/671` (BF16 / F16 tensors into `#f`) closed 2026-09-03,
wasm arm included -- so the fixture's wasm and wasm-component legs, listed under
Remaining below as waiting on it, are now only unrun.

`model.safetensors` is what Hugging Face's own model pages hold: a `u64` little-endian
header length, a JSON header `{ "<name>": { "dtype": "BF16", "shape": [..],
"data_offsets": [begin, end] }, ..., "__metadata__": {..} }`, then the tensor bytes,
each at its offsets from the end of the header, row-major. Every current small model is
BF16 in it (SmolLM2, TinyLlama, Qwen2.5 -- checked 2026-09-03); F16 and F32 occur in
older ones. The hyperparameters are a separate `config.json` and the tokenizer a
separate `tokenizer.json` (`.todo/674`).

## Do

1. `safetensors:read path &key only element-type` -> name -> array: the header through
   `rontolisp:json-parse` (`.kb/json.md`), each tensor by `read-sequence` -- F32
   straight into `#f`, BF16 / F16 through a staging `(unsigned-byte 16)` vector and
   `widen-float-bits` into `#f` (or `#bf16` once `.todo/484` exists), I64 / other
   dtypes refused by name. A Lisp library in the `gguf.lisp` shape; the staging loop
   is the `checkpoint` package (`checkpoint.lisp`), written here for `.todo/673` to
   share. **Not by `file-position`: it answers nil on every backend (`.todo/390`), so
   the reader walks each file front to back in offset order and skips an excluded
   tensor with `checkpoint:skip-bytes` -- a shard is opened once and walked once,
   whatever `:only` keeps.**
2. `config.json` read with `json-parse` into the same hyperparameter plist
   `llama2.lisp` uses (`hidden_size`, `intermediate_size`, `num_hidden_layers`,
   `num_attention_heads`, `num_key_value_heads`, `vocab_size`, `rope_theta`,
   `tie_word_embeddings`), and the HF tensor names (`model.embed_tokens.weight`,
   `model.layers.N.self_attn.{q,k,v,o}_proj.weight`, `mlp.{gate,up,down}_proj`,
   `input_layernorm`, `post_attention_layernorm`, `model.norm.weight`, `lm_head.weight`)
   mapped to that plist. HF's layout is the un-permuted one, so no Q/K fix-up here
   (unlike GGUF).
3. Sharded checkpoints: `model.safetensors.index.json` maps tensor name -> shard file.
   **Read it even for one shard** -- `Qwen/Qwen3.5-0.8B` ships its single shard as
   `model.safetensors-00001-of-00001.safetensors` with an index (2026-09-03), and
   SmolLM3-3B is two shards. It is a JSON lookup and a file per tensor group, not a
   feature.
4. Prefixes: a multimodal checkpoint keeps its language model under
   `model.language_model.` beside `model.visual.` and `mtp.` (Qwen3.5); `:only` /
   a prefix filter skips the tower and the speculative head, and the hyperparameters are
   under `text_config` in its `config.json`.
5. What the Qwen3.5 checkpoint needs beyond the name mapping, read off
   `modeling_qwen3_5.py` (2026-09-03, `.todo/677`; the GGUF converter does the same
   conversions at write time, so a GGUF already carries them -- `.todo/673` item 6):
   - **`Qwen3_5RMSNorm` computes `x * (1 + w)`**: add 1.0 to `input_layernorm`,
     `post_attention_layernorm`, `self_attn.q_norm`, `self_attn.k_norm` and `model.norm`
     before handing them to `llama2.lisp`, whose RMSNorm multiplies by the weight. Do
     NOT add it to `linear_attn.norm.weight` (`:ssm-norm`, `Qwen3_5RMSNormGated` uses
     the raw weight). Qwen3 (not 3.5) has the plain `x * w` norm.
   - `linear_attn.A_log` -> `:ssm-a` is **`-exp(A_log)`**, per head; `linear_attn.dt_bias`
     -> `:ssm-dt-bias` as is; `conv1d.weight [conv_dim, 1, 4]` -> `:ssm-conv` squeezed to a
     rank-2 `conv_dim x 4`; `in_proj_qkv` / `in_proj_z` / `in_proj_b` / `in_proj_a` /
     `out_proj` -> `:ssm-qkv` / `:ssm-z` / `:ssm-beta` / `:ssm-alpha` / `:ssm-out`
     (`deltanet.lisp` lists the shapes).
   - `self_attn.q_proj` is `[heads x (query 256 | gate 256), dim]` -- each head's 256
     query rows followed by its 256 gate rows (`torch.chunk(.., 2)` per head): split it
     into `:wq` and `:attn-gate`. The layout is `:rope :halves`, `:rotary-dim` 64
     (`head_dim 256 * partial_rotary_factor 0.25`), `rms_norm_eps` 1e-6 -> `:eps`.
   - `layer_types` says which block is `full_attention` (every 4th); `mtp.*` and
     `model.visual.*` are skipped by prefix.
6. LFM2.5 (`model_type lfm2`, `.todo/678`, `modeling_lfm2.py` read 2026-09-03):
   `layer_types` ("conv" / "full_attention") -> `:layer-types` (`:shortconv` /
   `:attention`); per conv block `model.layers.N.conv.{in_proj,out_proj}.weight` ->
   `:conv-in` / `:conv-out` and `conv.conv.weight [dim, 1, 3]` -> `:conv-w` squeezed
   to `dim x 3`; per attention block `self_attn.{q,k,v,out}_proj` (`out_proj`, not
   `o_proj`) and `q_layernorm` / `k_layernorm` -> `:q-norm` / `:k-norm`;
   `operator_norm` -> `:rms-att` (both kinds), `ffn_norm` -> `:rms-ffn`,
   `feed_forward.{w1,w2,w3}` -> `:w1` / `:w2` / `:w3` (8192 wide: `block_ff_dim`
   12288 auto-adjusted, read the shape), `model.embedding_norm` -> `:rms-final`, no
   `lm_head` (`tie_embedding`). `norm_eps` 1e-5 -> `:eps`, `rope_theta` 1e6,
   `head_dim` = 2048 / 32 = 64 (no field), `:rope :halves`. Plain `x * w` norms.

## Done (2026-09-03)

Shipped as two Lisp-source libraries in the `geom` shape: the `checkpoint` package
(`checkpoint.lisp`, `eval.CheckpointLibrary`: `make-tensor` / `stage-float-bits` /
`stage-float32` / `skip-bytes`, the staging every reader shares -- `.todo/673` calls it
too) and the `safetensors` package (`safetensors.lisp`, `eval.SafetensorsLibrary`:
`read` / `header` / `entries`), with `.kb/checkpoint-readers.md` for the three facts the
design rests on (no seek, 8-byte u16 elements, the unchecked `make-array`). Do 2-4 live
in `examples/llama2/llama2.lisp`'s `load-hf-checkpoint`: `config.json` (text_config
unwrapped) into the model plist, the HF names of llama / qwen3 / qwen3_5 / smollm3 /
granite / lfm2 mapped to the table's weights, the per-family conversions, the sharded
index, the language-model prefix filter. Verified: the fixture
(`examples/llama2/safetensors-check.lisp`, F32 / F16 / BF16 at rank 1-3 and a
one-element tensor, `:only`, the forced five-element chunking, the two-shard index) on
the interpreter and the JVM with and without `--simd`, `SafetensorsLibraryTest`, and
TinyLlama-1.1B-Chat's BF16 `model.safetensors` decoding coherent text through the
example on both (the README has the text).

## Remaining

- The WASM legs of the fixture (`examples.yaml` lists interpreter and jvm) once
  `.todo/671`'s WASM arm of `widen-float-bits` lands: add `wasm` and `wasm-component`.
- Load time and resident bytes, and the tok/s rows, measured on a QUIET box (the
  first measurements were taken under a load average above 30 and are not recorded):
  the README and `.todo/489`.
- A `#bf16` destination (`:element-type` passed through `checkpoint:make-tensor`) once
  `.todo/484` / `.todo/485` exist.

## Verify

- A checked-in synthetic fixture of a few KB (BF16, F16, F32 and one refused dtype,
  written by a throwaway script kept beside the test), read on all four backends
  (`ci-spec.yaml`), arrays compared element for element.
- TinyLlama-1.1B-Chat from its `model.safetensors`, into `#f`: coherent text
  (`.todo/489`); load time and resident bytes recorded in the example's README.
