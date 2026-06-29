# intersection

`(intersection list1 list2)`

`list1` と `list2` を集合として扱い、**両方**に現れる要素のリストを返します。要素は `eql` で比較されます。結果の要素の順序は未規定です。

```lisp
(intersection '(1 2 3) '(2 3 4)) ; => (3 2)
```
