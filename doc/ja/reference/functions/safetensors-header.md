# safetensors:header

`(safetensors:header path)`

1 つの `.safetensors` ファイルのパースされた JSON ヘッダ -- テンソル名 -> `{ "dtype", "shape", "data_offsets" }` のハッシュテーブルに、ファイルが持っていれば `"__metadata__"` を加えたもの -- と、第 2 値としてテンソルデータが始まるファイルオフセットを返します。ヘッダだけを読むので、2 GB のファイルが何を持っているかを尋ねる方法です。

```console
CL-USER> (multiple-value-bind (header start) (safetensors:header "model.safetensors")
           (list (hash-table-count header) start))
(203 23096)
CL-USER> (gethash "dtype" (gethash "lm_head.weight" (safetensors:header "model.safetensors")))
"BF16"
```
