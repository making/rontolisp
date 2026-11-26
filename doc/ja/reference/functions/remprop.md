# remprop

`(remprop symbol indicator)`

シンボルの属性リストから `indicator` の属性を削除します。属性が存在すれば真を、存在しなければ `nil` を返します。[`get`](get.md) / `(setf (get ...))` および [`symbol-plist`](symbol-plist.md) の相棒で、4 つとも同じプログラム全体で 1 つの名前キーのストアを読みます（シンボルには属性リストを吊り下げる同一性のセルがありません）。Common Lisp が保証するのは一般化ブール値だけです。ここでは `t` を返します（処理系によっては属性リストの残りを返します）。

```lisp
(setf (get 'my-node 'color) :red)
(setf (get 'my-node 'size) 3)
(list (remprop 'my-node 'color) (symbol-plist 'my-node) (remprop 'my-node 'color))
; => (T (SIZE 3) NIL)
```
