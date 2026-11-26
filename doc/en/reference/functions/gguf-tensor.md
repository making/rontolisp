# gguf:tensor

`(gguf:tensor file name)`

The packed float array loaded for the tensor `name`, or `nil` when it was not loaded -- `:metadata-only`, or an `:only` list that did not name it. An F32 tensor is read straight into the array in one transfer; F16 and BF16 arrive through `rontolisp:widen-float-bits`. The array's dimensions are the row-major ones [`gguf:tensor-info`](gguf-tensor-info.md) reports.

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
