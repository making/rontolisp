# pprint-exit-if-list-exhausted

`(pprint-exit-if-list-exhausted)`

[`pprint-logical-block`](pprint-logical-block.md) の中で、ブロックのリストに要素が残っていなければブロックを抜け、残っていれば `nil` を返します。ブロックの外ではエラーです。

```lisp
(with-output-to-string (s)
  (pprint-logical-block (s '(1 2 3) :prefix "<" :suffix ">")
    (loop (princ (pprint-pop) s)
          (pprint-exit-if-list-exhausted)
          (write-string "," s)))) ; => "<1,2,3>"
```
