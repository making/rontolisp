# set-difference

`(set-difference list1 list2 &key test key)`

両方を集合として扱い、`list2` に現れ**ない** `list1` の要素のリストを返します。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較される両方の要素へ適用されます。結果の要素の順序は未規定です。

```lisp
(set-difference '(1 2 3) '(2)) ; => (3 1)
```

```lisp
(set-difference '("a" "b") '("b" "c") :test #'string=) ; => ("a")
```
