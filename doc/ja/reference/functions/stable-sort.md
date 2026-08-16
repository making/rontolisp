# stable-sort

`(stable-sort sequence predicate &key key)`

[`sort`](sort.md) と同様に `sequence` をソートしますが、`predicate` が等しいとみなす要素 (`(predicate a b)` も `(predicate b a)` も真でない要素) の相対順序を保持します。省略可能な `:key` 関数は比較前に各要素へ適用されます。Common Lisp の破壊的な `stable-sort` と異なり、結果は常に新しいリストです — 引数は変更されず、文字列やベクタを渡した場合も要素のリストとして返ります。

```lisp
(stable-sort '((1 . b) (0 . a) (1 . a)) #'< :key #'car) ; => ((0 . A) (1 . B) (1 . A))
```

```lisp
(stable-sort '(3 1 2) #'<) ; => (1 2 3)
```
