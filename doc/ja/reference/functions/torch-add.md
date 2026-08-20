# torch:add

`(torch:add a b)`

numpy スタイルのブロードキャストを伴う、微分可能な要素ごとの `a + b` (`linalg:add`) です。オペランドはテンソル、数値、配列、リストのいずれでも構いません。backward はブロードキャストされた軸すべてで勾配を合計するため、`(b s d)` の活性に足した `(d)` のバイアスはあるべき `(d)` の勾配を受け取ります。

```lisp
(torch:data (torch:add (torch:tensor '((1.0 2.0) (3.0 4.0))) (torch:tensor '(10.0 20.0))))
; => #f((11.0 22.0) (13.0 24.0))
(torch:data (torch:add (torch:tensor '(1.0 2.0)) 0.5)) ; => #f(1.5 2.5)
```
