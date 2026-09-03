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
   **permutes Q and K** for its RoPE layout; the reader must undo that (or the forward
   pass must use the interleaved RoPE) -- the reference is `convert_hf_to_gguf.py`'s
   `permute`, and the story test is what catches getting it wrong.

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
