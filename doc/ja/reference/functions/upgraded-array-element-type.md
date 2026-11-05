# upgraded-array-element-type

`(upgraded-array-element-type typespec &optional environment)`

`typespec` を `:element-type` にして作った配列の要素型を返します。[`array-element-type`](array-element-type.md) が答えるものです。特殊化された配列が保持しない型は `t` になります。`environment` は無視されます。

```lisp
(list (upgraded-array-element-type 'character)
      (upgraded-array-element-type 'bit)
      (upgraded-array-element-type 't)) ; => (CHARACTER BIT T)
```
