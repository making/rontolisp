# adjustable-array-p

`(adjustable-array-p array)`

配列が [`make-array`](make-array.md) の `:adjustable` で作成された場合は `t` を、そうでなければ nil を返します。このフラグはそのまま報告されます。[`vector-push-extend`](vector-push-extend.md) はこのフラグに関係なく、フィルポインタを持つ任意のベクタを拡張します。

```lisp
(adjustable-array-p (make-array 2 :adjustable t)) ; => T
(adjustable-array-p (make-array 2)) ; => NIL
```
