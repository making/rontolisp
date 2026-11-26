# gguf:metadata

`(gguf:metadata file)`

The file's whole key/value block as a hash table, keyed by the string a GGUF writes: `general.architecture`, `llama.block_count`, `llama.attention.layer_norm_rms_epsilon`, `tokenizer.ggml.tokens` and so on. Every array value is a simple vector, whatever its element type, so a 49152-token vocabulary is one vector of strings. Use [`gguf:metadata-value`](gguf-metadata-value.md) for a single key.

```console
CL-USER> (gethash "general.name" (gguf:metadata *m*))
"Smollm2 135M 8k Lc100K Mix1 Ep2"
CL-USER> (hash-table-count (gguf:metadata *m*))
33
```
