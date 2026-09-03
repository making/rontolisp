# 673. Read a GGUF checkpoint

Difficulty: Medium

Part of `.todo/670`. Depends on `.todo/671` (F16 / BF16 tensors into `#f`); the Q8_0
tensors need `.todo/672` and decline with a clear error until it lands.

GGUF is the format a downloaded small model most often IS: one file with the
hyperparameters, the tokenizer and the tensors, in the width the publisher chose. The
model in this box's Hugging Face cache is one; `unsloth`, `bartowski` and `TheBloke`
publish every SLM as BF16 / F16 / Q8_0 / Q4_K_M GGUFs. Reading it is a binary walk over
`read-sequence` and the packed vectors that already exist -- no Java, a Lisp library in
the `geom.lisp` / `json.lisp` shape (`gguf.lisp`, its own `gguf:` package, spliced by a
`GgufLibrary` like the others, prunable per defun).

## The format (v3)

`"GGUF"`, `u32` version, `u64` tensor count, `u64` KV count; then the KV pairs -- a
string key, a `u32` type, a value (u8 / i8 / u16 / i16 / u32 / i32 / f32 / bool / string
/ array-of-type / u64 / i64 / f64; strings are `u64` length + UTF-8); then the tensor
infos -- name, `u32` n_dims, `u64` dims (ggml order: dims[0] is the fastest-varying, so a
`[cols, rows]` info is a `rows x cols` matrix), `u32` type, `u64` offset; then padding to
`general.alignment` (default 32); then the data, each tensor at its offset from the data
start, itself aligned. Everything little-endian. Type ids: 0 F32, 1 F16, 8 Q8_0 (blocks
of 34 bytes: an f16 scale + 32 int8), 30 BF16, and the K-quants (12 Q4_K, 14 Q6_K, ...).

## Do

1. `gguf:read` -> a struct: the metadata as a hash table (string -> value; arrays as
   vectors), the tensor infos (name, dims, type, offset), and the tensors as arrays
   loaded per the type: **F32** -> `#f` by `read-sequence` directly; **F16 / BF16** ->
   a `(unsigned-byte 16)` staging vector + `widen-float-bits` into `#f` (or `#bf16` once
   `.todo/484` exists; a `:element-type` argument chooses); **Q8_0** -> a
   `quantized-matrix` (`.todo/672`; the 34-byte blocks are de-interleaved into quants and
   scales, the scales through the f16 decoder); any other type -> an error naming the
   tensor, the type and the quant ("`blk.0.attn_q.weight` is Q4_K; supported: F32, F16,
   BF16, Q8_0"). Tensors are read in file order with `file-position` (`LispNames.FILE_POSITION`)
   to each offset, one bulk transfer per tensor, through a staging buffer of a few MB
   for the converted types -- a 2.2 GB model must not exist twice.
2. `:only` / `:filter` to load a subset by name (the tokenizer-only case, and a test that
   reads infos without the data).
3. The tokenizer fields surfaced as they are -- `tokenizer.ggml.model` (`llama` =
   SentencePiece-style with scores, `gpt2` = byte-level BPE with merges),
   `tokenizer.ggml.pre`, `tokens`, `scores`, `merges`, `token_type`, `bos_token_id`,
   `eos_token_id` -- for `.todo/674` and for `llama2.lisp`'s existing encoder, which can
   take `tokens` + `scores` in place of `tokenizer.bin` unchanged.
4. `examples/llama2/llama2.lisp`: a `load-checkpoint` variant over a GGUF whose
   `general.architecture` is `llama`, mapping `token_embd`, `blk.N.attn_{q,k,v,output}`,
   `blk.N.ffn_{gate,down,up}`, `blk.N.{attn,ffn}_norm`, `output_norm`, `output` (absent
   when tied) to the plist the `.bin` loader builds. Note that llama.cpp's converter
   **permutes Q and K** for its RoPE layout **-- for the families whose rope type is
   "normal" only** (`llama`, `smollm3`, `granite`: `LlamaModel.modify_tensors` and its
   subclasses; found 2026-09-03 by `.todo/677` reading `conversion/qwen.py`): a GGUF
   of those is `llama2.lisp`'s `:rope :pairs`. **`qwen3` / `qwen35` are NOT permuted**
   (`Qwen2Model` has no `permute`): their GGUF keeps HF's layout and is `:rope :halves`,
   exactly like a safetensors. The reader prepends the layout per architecture; the
   reference is `convert_hf_to_gguf.py`'s `permute`, and the story test is what catches
   getting it wrong for `llama` -- nothing catches it for Qwen but the coherence check.
5. The architecture names the newest models carry (headers read 2026-09-03), for
   `.todo/676`-`678`'s table: `qwen3` (adds `blk.N.attn_{q,k}_norm`), `qwen35`
   (`qwen35.full_attention_interval`, `ssm.*` keys, `nextn_predict_layers 1` -- the
   `block_count 25` includes the MTP block, which is skipped; the linear layers are
   `blk.N.ssm_{a,dt,alpha,beta,conv1d,norm,out}` + `attn_qkv` + `attn_gate`, norms and
   conv weights **F32** beside BF16 matrices), `lfm2` (`shortconv.*`; check the names in
   Liquid's own GGUF), `smollm3`, `granite`. Official Q4_0 files exist for all of them
   (ggml-org, Liquid): the refusal message must name Q8_0 / BF16 as the files to take
   instead.
6. What a `qwen35` GGUF holds, read off `conversion/qwen.py` and `src/models/qwen35.cpp`
   (2026-09-03, `.todo/677`) -- each of these is a silent-garbage bug if missed, so the
   weights plist `deltanet.lisp` documents is built with them in mind:
   - Every `*_norm.weight` (`attn_norm`, `attn_post_norm`, `attn_q_norm`, `attn_k_norm`,
     `output_norm`) is stored as **`1 + w`** -- the converter adds the 1 because
     `Qwen3_5RMSNorm` computes `x * (1 + w)` -- so the GGUF reader passes them AS IS.
     `blk.N.ssm_norm.weight` (the Gated DeltaNet's own gated norm, `:ssm-norm`) is
     the one exception: raw in both files.
   - `blk.N.ssm_a` is **`-exp(A_log)`** already (`SSM_A_NOSCAN`), as is -> `:ssm-a`;
     `dt_bias` is renamed `blk.N.ssm_dt.bias` -> `:ssm-dt-bias`.
   - `blk.N.attn_q.weight` is `[heads x (query 256 | gate 256), dim]` -- HF's `q_proj`
     interleave, each head's 256 query rows followed by its 256 gate rows, NOT split by
     the converter (llama.cpp views it with a stride). The reader splits it into `:wq`
     and `:attn-gate` (`.todo/676`'s gated attention).
   - `blk.N.attn_qkv.weight` is `[q | k | v]` in that order (no head reorder when
     `linear_num_key_heads == linear_num_value_heads`, true of every dense member);
     `blk.N.ssm_conv1d.weight` is `[conv_dim, 4]` (squeezed); `blk.N.attn_gate.weight`
     is `in_proj_z`; `ssm_beta` / `ssm_alpha` are `in_proj_b` / `in_proj_a`.
   - `qwen35.block_count` is 25 for the 24-layer model: the last block is the MTP
     (`nextn_predict_layers 1`) head, an attention-shaped block with `nextn.*` tensors
     -- skip it. `qwen35.rope.dimension_count` is 64 (partial rotary 0.25 of 256);
     `rope.dimension_sections` `[11 11 10 0]` is the vision MRoPE and reduces to 1-D RoPE
     for text.
   - `attention.layer_norm_rms_epsilon` is 1e-6 (Qwen3 too): prepend it as `:eps`.
7. What an `lfm2` GGUF holds, read off `conversion/lfm2.py` and `src/models/lfm2.cpp`
   (2026-09-03, `.todo/678`): NOT permuted (`LFM2Model` extends `TextModel`, not
   `LlamaModel`), so `:rope :halves`. Which block is which: there is no interval key --
   `lfm2.attention.head_count_kv` is a per-layer ARRAY with 0 for a conv block, and
   `llama.cpp` reads `n_head_kv(il) == 0` as "short conv"; the reader builds
   `:layer-types` from it (`:shortconv` / `:attention`). Per conv block
   `blk.N.shortconv.{in_proj,conv,out_proj}.weight` -> `:conv-in` / `:conv-w`
   (`[dim, L_cache]`, squeezed) / `:conv-out`; `blk.N.attn_norm` is `operator_norm`
   (the `:rms-att` of BOTH kinds), `blk.N.ffn_norm` the `:rms-ffn`; the final norm is
   `token_embd_norm` (`LLM_TENSOR_OUTPUT_NORM_LFM2`, HF's `embedding_norm`), no
   `output` (tied). `lfm2.shortconv.l_cache` 3, `attention.layer_norm_rms_epsilon`
   1e-5 -> `:eps`, `feed_forward_length` already auto-adjusted to 8192. Plain
   `x * w` norms, no offset.

## Verify

- A checked-in synthetic fixture of a few KB, written by a throwaway script kept beside
  the test: every KV type, one tensor per supported width, a Q4_K tensor to refuse, an
  alignment other than 32. Read on all four backends (`ci-spec.yaml`), compare the arrays
  element for element.
- `stories15M` converted to GGUF (llama.cpp's `convert_llama_ggml_to_gguf.py` or the
  `.bin` -> GGUF path; not checked in, a download or a script) tells the same story as
  the `.bin`, token for token, at temperature 0 -- the same check the example already
  makes against `run.c`.
- TinyLlama-1.1B as a BF16 GGUF, into `#f`: coherent text (`.todo/489`).
