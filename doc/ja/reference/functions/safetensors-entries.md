# safetensors:entries

`(safetensors:entries header)`

[`safetensors:header`](safetensors-header.md) が返したヘッダのテンソル情報を、`(name dtype shape begin end)` のリスト（shape はリスト、オフセットはデータ開始位置からの相対）として、`begin` でソートして返します。つまりファイルがテンソルを持つ順序であり、[`safetensors:read`](safetensors-read.md) が歩く順序です。`"__metadata__"` はエントリではありません。

```console
CL-USER> (first (safetensors:entries (safetensors:header "model.safetensors")))
("model.embed_tokens.weight" "BF16" (32000 2048) 0 131072000)
```
