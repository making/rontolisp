# array-has-fill-pointer-p

`(array-has-fill-pointer-p array)`

配列がフィルポインタを持つ場合 ([`make-array`](make-array.md) の `:fill-pointer` で作成された場合) は `t` を、そうでなければ nil を返します。フィルポインタを持てるのはランク 1 の配列 (ベクタ) のみです。

```lisp
(array-has-fill-pointer-p (make-array 3 :fill-pointer 0)) ; => t
(array-has-fill-pointer-p (make-array 3)) ; => NIL
```
