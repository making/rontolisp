# gguf:metadata-value

`(gguf:metadata-value file key &optional default)`

The value the file gives the metadata key `key`, or `default` when it has no such key. A GGUF boolean false is a stored `nil`, so absence is told from falsity by asking the table whether the key is present rather than by looking at the value: `default` really does mean absent.

```console
CL-USER> (gguf:metadata-value *m* "llama.embedding_length")
576
CL-USER> (gguf:metadata-value *m* "llama.attention.head_count_kv")
3
CL-USER> (gguf:metadata-value *m* "llama.rope.freq_base")
100000.0
CL-USER> (gguf:metadata-value *m* "no.such.key" :absent)
:ABSENT
```
