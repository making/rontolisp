# torch:tensorp

`(torch:tensorp x)`

`x` が torch のテンソル ([`torch:tensor`](torch-tensor.md) とすべての torch 演算が作る値) かどうかを返します。linalg 配列を含め、それ以外は `nil` です。

```lisp
(torch:tensorp (torch:tensor '(1 2)))  ; => T
(torch:tensorp #(1 2))                 ; => NIL
```
