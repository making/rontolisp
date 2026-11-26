# pprint-pop

`(pprint-pop)`

[`pprint-logical-block`](pprint-logical-block.md) の中で、ブロックのリストの次の要素を返します。残りがリストでないときは `. ` と残りを印字してブロックを抜け、`*print-length*` 個を出し切ったときは `...` を印字してブロックを抜けます。リストの終わりを過ぎたとき、およびオブジェクトが `nil` のブロックでは `nil` を返します。別のものを数え上げる本体にも `*print-length*` による打ち切りは効きます。ブロックの外ではエラーです。

```lisp
(with-output-to-string (s)
  (pprint-logical-block (s '(1 2 . 3) :prefix "(" :suffix ")")
    (loop (princ (pprint-pop) s) (write-string " " s)))) ; => "(1 2 . 3)"
```
