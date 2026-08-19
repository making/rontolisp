# torch:modulep

`(torch:modulep x)`

x が `torch` のモジュール ([`torch:module`](torch-module.md) が作る固定レイアウトのレコード) であれば `T`、そうでなければ `NIL` を返します。テンソルはモジュールではありません。

```lisp
(torch:modulep (torch:linear 2 2))  ; => T
(torch:modulep (torch:tensor 1.0))  ; => NIL
```
