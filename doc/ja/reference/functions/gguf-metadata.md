# gguf:metadata

`(gguf:metadata file)`

ファイルのキー/値ブロック全体をハッシュテーブルとして返します。キーは GGUF が書く文字列そのもの（`general.architecture`、`llama.block_count`、`llama.attention.layer_norm_rms_epsilon`、`tokenizer.ggml.tokens` など）です。配列の値は要素型によらず単純ベクタになるので、49152 語の語彙は文字列 1 本のベクタです。単一のキーを引くには [`gguf:metadata-value`](gguf-metadata-value.md) を使います。

```console
CL-USER> (gethash "general.name" (gguf:metadata *m*))
"Smollm2 135M 8k Lc100K Mix1 Ep2"
CL-USER> (hash-table-count (gguf:metadata *m*))
33
```
