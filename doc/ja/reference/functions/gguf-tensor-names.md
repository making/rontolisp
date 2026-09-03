# gguf:tensor-names

`(gguf:tensor-names file)`

ファイルのテンソル名を、ディレクトリに並んでいる順で返します。`llama` 系のチェックポイントでは `token_embd.weight`、`blk.N.attn_{q,k,v,output}.weight`、`blk.N.ffn_{gate,down,up}.weight`、`blk.N.{attn,ffn}_norm.weight`、`output_norm.weight`、`output.weight`（分類ヘッドが埋め込みと共有されている場合は存在しません）という名前です。

```console
CL-USER> (subseq (gguf:tensor-names *m*) 0 3)
("token_embd.weight" "blk.0.attn_norm.weight" "blk.0.ffn_down.weight")
CL-USER> (length (gguf:tensor-names *m*))
272
```
