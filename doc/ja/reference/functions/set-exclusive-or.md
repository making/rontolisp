# set-exclusive-or

`(set-exclusive-or list1 list2 &key test test-not key)`

2 つのリストの対称差(どちらか一方にしかない要素)を返します。既定では `eql` で比較します。`:test` には関数指定子を渡し、`:test-not` を渡すとその関数が偽を返す箇所を一致とみなします。`:key` は比較される両方の要素へ適用されるセレクタです。比較は常に `list1` 側の要素を第 1 引数として行われます(どちらの走査でも同じ)。結果は `list1` にしかない要素を順に並べ、続いて `list2` にしかない要素を並べます(Common Lisp では順序は未規定です)。

```lisp
(set-exclusive-or '(1 2 3) '(2 3 4)) ; => (1 4)
```

```lisp
(set-exclusive-or '("a" "b") '("B" "c") :test #'string-equal) ; => ("a" "c")
```

```lisp
(set-exclusive-or '((1 a) (2 b)) '((2 x)) :key #'car) ; => ((1 A))
```
