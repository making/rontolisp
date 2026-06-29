# nthcdr

`(nthcdr n list)`

`list` に `cdr` を `n` 回適用し、その結果の末尾、すなわち先頭の `n` 個の要素を飛ばしたサブリストを返します。`n` がリストの長さに達するか超える場合、結果は `nil` になります。`(nthcdr 0 list)` はリストをそのまま返します。

```lisp
(nthcdr 2 '(a b c d)) ; => (c d)
```
