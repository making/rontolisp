# eval-when

`(eval-when (situation...) body...)`

本体を実行するかどうかは、フォームの位置で決まります（CLHS 3.2.3.1）。状況指定は `:compile-toplevel`、`:load-toplevel`、`:execute`、および非推奨の `compile`/`load`/`eval` 表記です。

**トップレベル** -- プログラムやそれがロードするファイルのフォーム、トップレベルの `progn`、`locally`、`macrolet`、`symbol-macrolet`、`eval-when` のメンバー、そこにあるマクロ呼び出しの展開形 -- では、どの状況指定でも本体を実行します。プログラムはコンパイルとロードの両方を兼ねるからです。空の状況リストはどの状況も指定しないので、何も実行しません。

コンパイルパスでは本体のフォームが周囲のプログラムにスプライスされるため、よくあるマクロエクスポートのイディオム -- `(eval-when (:compile-toplevel :load-toplevel :execute) ...)` に包まれた `defmacro` -- はコンパイル単位の残りの部分に対してマクロを定義し、ネストした `defun` も通常のトップレベル定義と同様に収集されます。`:compile-toplevel` のフォームはコンパイル時にも実行されるので、後続のマクロから使えます。マクロ呼び出しの展開形そのものが `eval-when` である場合、コンパイルパスは `compile-file` と同じように読みます。`:compile-toplevel` はコンパイル時にだけ本体を実行し、`:load-toplevel` はプログラムに残し、`:execute` だけのものは取り除きます。

**それ以外の位置** -- 関数本体、`let`、[`eval`](../functions/eval.md) が実行するフォーム -- では、全バックエンド共通で `:execute`（または `eval`）のときだけ本体を実行し、それ以外では `nil` を返します。

```lisp
(progn
  (eval-when (:compile-toplevel :load-toplevel :execute)
    (defun ew-double (x) (* x 2)))
  (ew-double 21)) ; => 42
(let () (eval-when (:load-toplevel) 1)) ; => NIL
(let () (eval-when (:execute) 2)) ; => 2
```
