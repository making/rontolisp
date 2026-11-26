# safetensors:entries

`(safetensors:entries header)`

The tensor infos of a header from [`safetensors:header`](safetensors-header.md) as a list of `(name dtype shape begin end)` -- the shape a list, the offsets relative to the data start -- sorted by `begin`, i.e. in the order the file holds them and the order [`safetensors:read`](safetensors-read.md) walks them. `"__metadata__"` is not an entry.

```console
CL-USER> (first (safetensors:entries (safetensors:header "model.safetensors")))
("model.embed_tokens.weight" "BF16" (32000 2048) 0 131072000)
```
