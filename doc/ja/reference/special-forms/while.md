# while

`(while test body...)`

`test` を評価し、それが nil でない間、`body` のフォームを順に評価して繰り返します。ループは `test` が `nil` に評価された時点で終了し、`while` 自身は常に `nil` を返します。これは副作用を伴う反復に使われ、通常は本体内で `setq` を使って変数を更新します。

```lisp
(let ((i 0) (sum 0))
  (while (< i 5)
    (setq sum (+ sum i))
    (setq i (+ i 1)))
  sum) ; => 10
```
