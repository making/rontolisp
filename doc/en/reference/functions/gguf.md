# gguf Package Functions

The `gguf` package reads a **GGUF checkpoint** -- the single file a downloaded
small language model most often is, carrying the hyperparameters, the tokenizer
and the weights together in the width the publisher chose. It is written in
rontolisp itself and loaded on first use like `linalg` and `geom`.

It is **not part of Common Lisp**; reference its names with the `gguf:`
qualifier. It reaches for nothing but the standard package and ordinary file
I/O, so it runs on every backend that has a filesystem, with the same answer on
all of them.

Two things worth knowing before you read one:

- **The tokenizer and the hyperparameters are free.** They live in the key/value
  block at the front of the file, so `(gguf:read path :metadata-only t)` stops
  before the weights and never touches the gigabytes.
- **F32, F16 and BF16 load into packed float arrays, Q8_0 into a quantized
  matrix** ([`rontolisp:quantize`](rontolisp-quantize.md); interpreter and JVM
  only). **Any other quantized tensor is refused when its body is asked for, and
  never earlier**: a Q4_K_M file still opens, still lists its whole directory
  and still hands over its vocabulary.

| Function | Example | Result |
|----------|---------|--------|
| `gguf:read` | `(gguf:read "model.gguf" :metadata-only t)` | reads a checkpoint; `:metadata-only` stops before the weights, `:only` names the tensors to load |
| `gguf:version` | `(gguf:version f)` | the GGUF version the file declares (3 for everything current) |
| `gguf:metadata` | `(gguf:metadata f)` | the whole key/value block as a hash table, arrays as simple vectors |
| `gguf:metadata-value` | `(gguf:metadata-value f "llama.block_count")` | one key's value, or a default that really means absent |
| `gguf:tensor-names` | `(gguf:tensor-names f)` | the tensor names, in directory order |
| `gguf:tensor-info` | `(gguf:tensor-info f "token_embd.weight")` | one tensor's directory entry: dims (ROW-MAJOR), type, elements, bytes, offset |
| `gguf:tensor` | `(gguf:tensor f "output_norm.weight")` | the packed float array (a quantized matrix for a Q8_0 tensor) loaded for a tensor, or `nil` when it was not loaded |
| `gguf:tokenizer-fields` | `(gguf:tokenizer-fields f)` | the tokenizer fields as a plist, in the shape `tokenizer:make-bpe` takes |
