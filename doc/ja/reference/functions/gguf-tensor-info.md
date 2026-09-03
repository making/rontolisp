# gguf:tensor-info

`(gguf:tensor-info file name)`

テンソル `name` のディレクトリエントリを plist で返します。`:name`、`:dims`、`:type`（ggml の型 id）、`:type-name`（`"F32"`、`"F16"`、`"BF16"`、`"Q4_K"` など）、`:elements`、`:bytes`、`:offset`（データ領域先頭からの位置）です。

**`:dims` は行優先です。**ggml は次元を最速変化の軸から並べるので、埋め込みに対してファイルが持つ `[576 49152]` は 49152x576 の行列です。リーダは読み込み時に一度だけ反転するので、以降は誰も順序を覚えておく必要がありません。ブロック形状をこのリーダが知らない型では `:bytes` は `nil` になりますが、走査は「このテンソルのサイズの先」ではなく「次のテンソルが宣言したオフセット」へ進むので、それで困ることはありません。

```console
CL-USER> (gguf:tensor-info *m* "blk.0.attn_q.weight")
(:NAME "blk.0.attn_q.weight" :DIMS (576 576) :TYPE 1 :TYPE-NAME "F16"
 :OFFSET 62820864 :ELEMENTS 331776 :BYTES 663552)
CL-USER> (getf (gguf:tensor-info *m* "token_embd.weight") :dims)
(49152 576)
```
