# 675. Read a safetensors checkpoint

Difficulty: Low

Part of `.todo/670`. Depends on `.todo/671` (BF16 / F16 tensors into `#f`).

`model.safetensors` is what Hugging Face's own model pages hold: a `u64` little-endian
header length, a JSON header `{ "<name>": { "dtype": "BF16", "shape": [..],
"data_offsets": [begin, end] }, ..., "__metadata__": {..} }`, then the tensor bytes,
each at its offsets from the end of the header, row-major. Every current small model is
BF16 in it (SmolLM2, TinyLlama, Qwen2.5 -- checked 2026-09-03); F16 and F32 occur in
older ones. The hyperparameters are a separate `config.json` and the tokenizer a
separate `tokenizer.json` (`.todo/674`).

## Do

1. `safetensors:read path &key only element-type` -> name -> array: the header through
   `rontolisp:json-parse` (`.kb/json.md`), each tensor by `file-position` +
   `read-sequence` -- F32 straight into `#f`, BF16 / F16 through a staging
   `(unsigned-byte 16)` vector and `widen-float-bits` into `#f` (or `#bf16` once
   `.todo/484` exists), I64 / other dtypes refused by name. A Lisp library in the
   `gguf.lisp` shape; if `.todo/673` lands first, share its staging loop.
2. `config.json` read with `json-parse` into the same hyperparameter plist
   `llama2.lisp` uses (`hidden_size`, `intermediate_size`, `num_hidden_layers`,
   `num_attention_heads`, `num_key_value_heads`, `vocab_size`, `rope_theta`,
   `tie_word_embeddings`), and the HF tensor names (`model.embed_tokens.weight`,
   `model.layers.N.self_attn.{q,k,v,o}_proj.weight`, `mlp.{gate,up,down}_proj`,
   `input_layernorm`, `post_attention_layernorm`, `model.norm.weight`, `lm_head.weight`)
   mapped to that plist. HF's layout is the un-permuted one, so no Q/K fix-up here
   (unlike GGUF).
3. Sharded checkpoints (`model.safetensors.index.json`): out of scope below ~2B, say so
   in the error.

## Verify

- A checked-in synthetic fixture of a few KB (BF16, F16, F32 and one refused dtype,
  written by a throwaway script kept beside the test), read on all four backends
  (`ci-spec.yaml`), arrays compared element for element.
- TinyLlama-1.1B-Chat from its `model.safetensors`, into `#f`: coherent text
  (`.todo/489`); load time and resident bytes recorded in the example's README.
