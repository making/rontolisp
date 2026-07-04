# intersection

`(intersection list1 list2 &key test key)`

`list1` と `list2` を集合として扱い、**両方**に現れる要素のリストを返します。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較される両方の要素へ適用されます。結果の要素の順序は未規定です。

```lisp
(intersection '(1 2 3) '(2 3 4)) ; => (3 2)
```

```lisp
(intersection '("a" "b") '("b" "c") :test #'string=) ; => ("b")
```
