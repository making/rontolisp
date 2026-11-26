# gguf:tensor-names

`(gguf:tensor-names file)`

The file's tensor names, in the order its directory lists them. A `llama`-family checkpoint names them `token_embd.weight`, `blk.N.attn_{q,k,v,output}.weight`, `blk.N.ffn_{gate,down,up}.weight`, `blk.N.{attn,ffn}_norm.weight`, `output_norm.weight` and `output.weight` (absent when the classifier is tied to the embedding).

```console
CL-USER> (subseq (gguf:tensor-names *m*) 0 3)
("token_embd.weight" "blk.0.attn_norm.weight" "blk.0.ffn_down.weight")
CL-USER> (length (gguf:tensor-names *m*))
272
```
