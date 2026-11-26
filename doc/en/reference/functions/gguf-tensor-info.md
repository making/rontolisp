# gguf:tensor-info

`(gguf:tensor-info file name)`

The directory entry for the tensor `name`, as a plist: `:name`, `:dims`, `:type` (the ggml type id), `:type-name` (`"F32"`, `"F16"`, `"BF16"`, `"Q4_K"`, ...), `:elements`, `:bytes` and `:offset` (from the start of the data).

**`:dims` is ROW-MAJOR.** ggml stores dimensions fastest-varying first, so the `[576 49152]` the file carries for the embedding is a 49152x576 matrix; the reader reverses them once, on the way in, so nothing downstream has to remember. `:bytes` is `nil` for a type whose block shape this reader does not know -- which costs nothing, because the walk moves to the next tensor's declared offset rather than past this one's size.

```console
CL-USER> (gguf:tensor-info *m* "blk.0.attn_q.weight")
(:NAME "blk.0.attn_q.weight" :DIMS (576 576) :TYPE 1 :TYPE-NAME "F16"
 :OFFSET 62820864 :ELEMENTS 331776 :BYTES 663552)
CL-USER> (getf (gguf:tensor-info *m* "token_embd.weight") :dims)
(49152 576)
```
