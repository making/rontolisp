# safetensors Package Functions

The `safetensors` package reads the file a Hugging Face model page holds --
`model.safetensors`, or a sharded `model.safetensors.index.json` -- into packed
float arrays, without Python and without a conversion step. It is written in
rontolisp itself over the [`checkpoint`](checkpoint.md) staging package,
[`rontolisp:json-parse`](json-parse.md) and the byte-stream primitives, loaded on
first use like `geom`, and it runs on every backend that has a filesystem. It is
**not part of Common Lisp**; reference its names with the `safetensors:`
qualifier -- `safetensors:read` is the package's own symbol, not `cl:read`.

The format: a little-endian `u64` header length, that many bytes of JSON --
`{ "<name>": { "dtype": "BF16", "shape": [rows, cols], "data_offsets": [begin,
end] }, ..., "__metadata__": { ... } }` -- then the tensor bytes, row-major, at
their offsets from the end of the header. F32, F16 and BF16 tensors are read;
any other dtype is an error naming the tensor. A file is walked front to back
(streams do not reposition), and a tensor the `:only` predicate excludes is
skipped, not staged, so a multimodal checkpoint's vision tower costs its bytes
of I/O and nothing else. `examples/llama2/llama2.lisp` reads TinyLlama, Qwen3.5
and LFM2.5 checkpoints through it.

| Function | Example | Result |
|----------|---------|--------|
| `safetensors:read` | `(safetensors:read "TinyLlama-1.1B-Chat-v1.0")` | a hash table, tensor name -> packed float array |
| `safetensors:header` | `(safetensors:header "model.safetensors")` | the parsed JSON header, and the offset the data starts at |
| `safetensors:entries` | `(safetensors:entries header)` | the tensor infos as `(name dtype shape begin end)`, in file order |
