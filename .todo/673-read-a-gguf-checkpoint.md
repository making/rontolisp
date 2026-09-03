# 673. Read a GGUF checkpoint

Difficulty: Medium

Part of `.todo/670`. `.todo/671` (F16 / BF16 tensors into `#f`) closed 2026-09-03.
The reader half of this item landed the same day -- see Done below -- and the Q8_0
tensors decline by name until `.todo/672`. What is left is the model half, in
`examples/llama2/llama2.lisp`.

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
   BF16, Q8_0"). Tensors are read in file order, one bulk transfer per tensor, through a staging
   buffer of a few MB for the converted types -- a 2.2 GB model must not exist twice.
   **Not with `file-position`: it answers nil on every backend (`.todo/390`), so the
   reader WALKS the file front to back in offset order and passes over a tensor it
   was told not to load with `checkpoint:skip-bytes` (bounded reads, never staged) --
   the shape `.todo/675`'s reader has, over the same `checkpoint` package
   (`checkpoint:make-tensor` / `stage-float-bits` / `stage-float32` / `skip-bytes`,
   `src/main/resources/am/ik/rontolisp/eval/checkpoint.lisp`), which is also the
   staging loop to share rather than write again.** GGUF's tensor infos carry offsets
   in ascending order, so a sequential walk costs nothing a seek would have saved.
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
   - Every `*_norm.weight` (`attn_norm`, `post_attention_norm` -- NOT `ffn_norm`, the
     feed-forward norm's name in every other family -- `attn_q_norm`, `attn_k_norm`,
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

## Done (2026-09-03): items 1-3, the READER

Shipped as the `gguf` package -- `gguf.lisp` + `eval.GgufLibrary` in the `geom` shape,
`.kb/gguf.md` -- with eight exports: `read` (`:only` / `:metadata-only` /
`:element-type`), `version`, `metadata`, `metadata-value`, `tensor-names`,
`tensor-info`, `tensor`, `tokenizer-fields`. Docs for all eight in both trees plus the
package page, the catalog and the nav.

Three findings the plan did not have:

- **`file-position` seeks nothing** (`.todo/390`), measured on three backends before the
  design was fixed: the data is walked SEQUENTIALLY in ascending offset order and the
  reader counts its own byte position. The plan above was rewritten around it, and the
  consequences are in `.kb/gguf.md` -- the tokenizer and the hyperparameters are FREE
  (the key/value block is at the front), `:only` saves memory and conversion but not
  I/O, and a file whose offsets descend is refused by name.
- **The staging is the `checkpoint` package's**, adopted the moment `.todo/675` landed
  it: `make-tensor` / `stage-float32` / `stage-float-bits` / `skip-bytes`. It also
  corrected a number this reader had recorded -- a packed `(unsigned-byte 16)` vector is
  a `long[]`, EIGHT bytes an element, so the first cut's whole-tensor staging cost four
  times the tensor's size on disk rather than twice.
- **A quantized tensor is refused when its BODY is asked for and never earlier**, so a
  Q4_K_M or Q8_0 checkpoint still opens, still lists its whole directory and still hands
  over its vocabulary. That is what `.todo/672` starts from.

Verified: the synthetic fixture written by llama.cpp's OWN `gguf` Python writer
(`src/test/resources/gguf/synthetic.gguf`, all thirteen value types, alignment 64, rank
1/2/3, one tensor per loadable width, a Q8_0 and a Q4_K to refuse) agreeing with the
official reader field for field in `GgufLibraryTest`; `ci-spec.yaml`'s
`gguf-cross-backend` on all four backends; and by hand against two real checkpoints --
`tinyllamas/stories15M-q4_0.gguf` (57 tensors, F32 norms to the last digit, the Q4_0 /
Q8_0 refusals, a 32000-piece SentencePiece vocabulary through
`tokenizer:make-sentencepiece`) and `SmolLM2-135M-Instruct-f16.gguf` (272 tensors,
F16 bodies, 49152 tokens + 48900 merges through `tokenizer:make-bpe` giving ids
IDENTICAL to the Python `tokenizers` library's).

## Done (2026-09-03, `.todo/677`): items 4-7, the MODEL side

`examples/llama2/llama2.lisp`'s `load-gguf-checkpoint`: `general.architecture` picks
the `*architectures*` row; the hyperparameters come from `<arch>.embedding_length`,
`block_count` minus `nextn_predict_layers` (Qwen3.5's MTP block is the last one and is
passed over by name), `attention.head_count` / `head_count_kv` (an integer, or LFM2's
per-layer array -- its zeros are the `:shortconv` blocks, `gguf-layer-types`),
`attention.key_length` (the head dim when it is not `dim / heads`),
`attention.layer_norm_rms_epsilon`, `rope.freq_base`, `rope.dimension_count`,
`full_attention_interval`, `context_length`; the tensors are read with `:only` over the
language model's names and mapped onto the same weights plist the safetensors loader
builds (`attn_norm`, `ffn_norm` or Qwen3.5's `post_attention_norm`, `attn_{q,k,v,output}`,
`attn_{q,k}_norm`, `ffn_{gate,up,down}`, the `ssm_*` / `attn_qkv` / `attn_gate` of a
Gated DeltaNet block, `shortconv.*` of an LFM2 block, `token_embd`, `output_norm` or
`token_embd_norm`, `output` when present); a missing per-block tensor is an error naming
it (the first run died on `ffn_norm` with a "mixed single-float and double-float" from
the nil downstream). What the converter did is left as it is (the `1 + w` norms,
`-exp(A_log)`, the squeezed conv), and what it did not do -- Qwen3.5's `attn_q` query |
gate interleave -- is split here, told by the row count. The RoPE layout is `:pairs`
for `llama` / `smollm3` / `granite` and `:halves` otherwise (the permute rule above).
The tokenizer comes from `gguf:tokenizer-fields` into `tokenizer:make-bpe` (kind =
`tokenizer.ggml.pre`, the control-type tokens as specials, BOS only when
`tokenizer.ggml.add_bos_token` says so) or `tokenizer:make-sentencepiece`.

Verified: ggml-org's `Qwen3.5-0.8B-BF16.gguf` answers the same prompt with the same
text as the safetensors, token for token, at temperature 0 (`.todo/677`), and the
`Q8_0` file is refused by name at `token_embd.weight` with the message above.

## Remaining

- **Q8_0 bodies** (`.todo/672`). The refusal names the type and points at
  `:metadata-only` / `:only`; replacing it is a change to `gguf::%read-tensor`'s one
  `(= type 8)` branch and to the one `handler-case` in the ci-spec case.
- **A `#bf16` destination** once `.todo/484` / `.todo/485` are through: `:element-type`
  already threads to `checkpoint:make-tensor`, so it is a keyword and a test.
- The `stories15M`-as-GGUF story check and the TinyLlama BF16 GGUF run, `.todo/489`'s
  rungs: the loader is there, the files are not on the box.

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
