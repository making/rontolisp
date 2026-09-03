# gguf:tensor

`(gguf:tensor file name)`

テンソル `name` として読み込まれたパック済み浮動小数点配列を返します。読み込まれていない場合（`:metadata-only` のとき、または `:only` に含まれていなかったとき）は `nil` です。F32 のテンソルは 1 回の転送で配列に直接読み込まれ、F16 と BF16 は `rontolisp:widen-float-bits` を通ります。配列の次元は [`gguf:tensor-info`](gguf-tensor-info.md) が返す行優先のものです。

```console
CL-USER> (defparameter *f* (gguf:read "SmolLM2-135M-Instruct-f16.gguf"
                                      :only '("blk.0.attn_q.weight")))
*F*
CL-USER> (array-dimensions (gguf:tensor *f* "blk.0.attn_q.weight"))
(576 576)
CL-USER> (aref (gguf:tensor *f* "blk.0.attn_q.weight") 0 0)
-0.0751953125
CL-USER> (gguf:tensor *f* "token_embd.weight")
NIL
```
