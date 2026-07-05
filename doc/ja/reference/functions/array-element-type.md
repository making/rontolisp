# array-element-type

`(array-element-type array)`

配列の要素型を返します。要素型は追跡されません (すべての配列は任意の値を保持でき、[`make-array`](make-array.md) は `:element-type` を受け付けますが無視します) ので、結果は常にシンボル `t` です。cl-utilities の `copy-array` のようなポータブルなコードとの互換性のために提供されています。

```lisp
(array-element-type (make-array 3)) ; => t
```
