# torch:optimizerp

`(torch:optimizerp x)`

`x` が torch のオプティマイザ ([`torch:optimizer`](torch-optimizer.md) が作る固定レイアウトのレコード) であれば `T` を、テンソルやモジュールを含むそれ以外では `NIL` を返します。

```lisp
(torch:optimizerp (torch:sgd nil))       ; => T
(torch:optimizerp (torch:tensor '(1.0))) ; => NIL
(torch:optimizerp 42)                    ; => NIL
```
