# eval-when

`(eval-when (situation...) body...)`

本体を `progn` として評価します。すべての状況指定（`:compile-toplevel`、`:load-toplevel`、`:execute`、および非推奨の `compile`/`load`/`eval` 表記）は「今評価する」として扱われます。状況リストは必須ですがそれ以外は無視されるため、本物の Common Lisp の状況規則の下で動くコードはここでも動きます。

トップレベルではコンパイルパス上で本体のフォームが周囲のプログラムにスプライスされるため、よくあるマクロエクスポートのイディオム -- `(eval-when (:compile-toplevel :load-toplevel :execute) ...)` に包まれた `defmacro` -- はコンパイル単位の残りの部分に対してマクロを定義し、ネストした `defun` も通常のトップレベル定義と同様に収集されます。

```lisp
(progn
  (eval-when (:compile-toplevel :load-toplevel :execute)
    (defun ew-double (x) (* x 2)))
  (ew-double 21)) ; => 42
```
