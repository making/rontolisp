# adjustable-array-p

`(adjustable-array-p array)`

配列が [`make-array`](make-array.md) の `:adjustable` で作成された場合は `t` を、そうでなければ nil を返します。このフラグはそのまま報告されます。[`vector-push-extend`](vector-push-extend.md) はこのフラグに関係なく、フィルポインタを持つ任意のベクタを拡張します。

文字列は文字のランク 1 配列なので、これも同様に答えます。リテラル文字列は調整可能ではなく、`:adjustable` で作成した文字ベクタは調整可能です。

```lisp
(adjustable-array-p (make-array 2 :adjustable t)) ; => T
(adjustable-array-p (make-array 2)) ; => NIL
(adjustable-array-p "abc") ; => NIL
(adjustable-array-p (make-array 2 :element-type 'character :adjustable t)) ; => T
```
