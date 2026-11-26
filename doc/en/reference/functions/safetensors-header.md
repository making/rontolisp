# safetensors:header

`(safetensors:header path)`

The parsed JSON header of one `.safetensors` file -- a hash table, tensor name -> `{ "dtype", "shape", "data_offsets" }` plus `"__metadata__"` when the file carries one -- and, as a second value, the file offset the tensor data starts at. Reads only the header: the way to ask a 2 GB file what it holds.

```console
CL-USER> (multiple-value-bind (header start) (safetensors:header "model.safetensors")
           (list (hash-table-count header) start))
(203 23096)
CL-USER> (gethash "dtype" (gethash "lm_head.weight" (safetensors:header "model.safetensors")))
"BF16"
```
