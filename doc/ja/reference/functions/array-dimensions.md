# array-dimensions

`(array-dimensions array)`

`array` の各次元のサイズをリストにして返します。ランク 1 のベクタでは要素 1 つのリスト、ランク 2 の配列では要素 2 つのリストになります (サポートされるのはランク 1 と 2 のみ)。単一の軸のサイズは [`array-dimension`](array-dimension.md)、次元数は [`array-rank`](array-rank.md) を参照してください。

```lisp
(array-dimensions (make-array '(2 3))) ; => (2 3)
(array-dimensions (vector 1 2)) ; => (2)
```
