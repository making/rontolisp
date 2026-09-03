# safetensors:read

`(safetensors:read path &key only element-type)`

Reads a safetensors checkpoint into a hash table (`equal`, string keys) of tensor name -> packed float array of the file's shape: a rank-1 vector for a one-dimensional tensor, a rank-N packed array otherwise. `path` is a `.safetensors` file, a `model.safetensors.index.json` (its shards are each opened once and walked once), or a directory holding either. `element-type` is `single-float` (the default) or `double-float`; F32 tensors are read straight in, F16 and BF16 through the [`checkpoint`](checkpoint.md) staging, and any other dtype signals an error naming the tensor and the dtype. `only` is a predicate over the name: a tensor it rejects is skipped in bounded reads rather than staged, which is how a multimodal checkpoint's tower and speculative head are left on disk.

```console
CL-USER> (defparameter *w* (safetensors:read "TinyLlama-1.1B-Chat-v1.0"
                                             :only (lambda (name) (search "layers.0." name))))
*W*
CL-USER> (hash-table-count *w*)
9
CL-USER> (array-dimensions (gethash "model.layers.0.self_attn.q_proj.weight" *w*))
(2048 2048)
CL-USER> (array-element-type (gethash "model.layers.0.input_layernorm.weight" *w*))
SINGLE-FLOAT
```

## Backend support

Every backend that has a filesystem: the interpreter and a compiled `.class`/`.jar` today; the WASM backends once `rontolisp:widen-float-bits` is there (F32 tensors need no widening and read everywhere).
