# nthcdr

`(nthcdr n list)`

`list` に `cdr` を `n` 回適用し、その結果の末尾、すなわち先頭の `n` 個の要素を飛ばしたサブリストを返します。`n` がリストの長さに達するか超える場合、結果は `nil` になります。`(nthcdr 0 list)` はリストをそのまま返します。`n` 回の cdr を終える前にリストでない値に達すると (`(nthcdr 1 5)`)、`type-error` を通知します。

```lisp
(nthcdr 2 '(a b c d)) ; => (C D)
```
