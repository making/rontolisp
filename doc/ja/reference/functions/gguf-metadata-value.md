# gguf:metadata-value

`(gguf:metadata-value file key &optional default)`

メタデータのキー `key` に対する値を返します。そのキーがなければ `default` です。GGUF の真偽値 false は `nil` として格納されるため、「無い」と「偽」は値では区別できません。この関数はテーブルにキーが存在するかどうかを見て区別するので、`default` が返るのは本当にキーが無いときだけです。

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
