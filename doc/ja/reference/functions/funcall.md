# funcall

`(funcall function &rest args)`

`function` を与えられた引数で呼び出し、その結果を返します。`function` 引数には関数値 (`#'name`、`lambda`、または `symbol-function` の結果) か、関数を指すクオートされたシンボル (`(funcall 'car x)`) を指定できます。インタプリタは実行時にシンボルを解決し、コンパイラは関数位置のリテラル `(quote name)` を `(function name)` に書き換えます。

```lisp
(funcall #'+ 3 4) ; => 7
```
