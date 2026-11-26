# array-has-fill-pointer-p

`(array-has-fill-pointer-p array)`

配列がフィルポインタを持つ場合 ([`make-array`](make-array.md) の `:fill-pointer` で作成された場合) は `t` を、そうでなければ nil を返します。フィルポインタを持てるのはランク 1 の配列 (ベクタ) のみです。

文字列は文字のランク 1 配列なので、これも同様に答えます。リテラル文字列は持たず、`:fill-pointer` で作成した文字ベクタは持ちます。

```lisp
(array-has-fill-pointer-p (make-array 3 :fill-pointer 0)) ; => T
(array-has-fill-pointer-p (make-array 3)) ; => NIL
(array-has-fill-pointer-p "abc") ; => NIL
(array-has-fill-pointer-p (make-array 3 :element-type 'character :fill-pointer 0)) ; => T
```
