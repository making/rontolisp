# set-difference

`(set-difference list1 list2)`

両方を集合として扱い、`list2` に現れ**ない** `list1` の要素のリストを返します。要素は `eql` で比較されます。結果の要素の順序は未規定です。

```lisp
(set-difference '(1 2 3) '(2)) ; => (3 1)
```
