# with-simple-restart

`(with-simple-restart (restart-name format-control format-arg...) body...)`

[`restart-case`](restart-case.md) の糖衣です: `restart-name` という名前のリスタートを確立して `body...` を評価します。リスタートが起動されると `with-simple-restart` フォームから `(values nil t)` が返るため、呼び出し側は「本体が中断された」ことと「本体が nil を返した」ことを区別できます。フォーマット制御はリスタートのレポートになります(lite: フォーマット引数は受理された上で捨てられます — レポートを描画するものはありません)。正常終了時には本体の値が返ります。`--no-gc` を除くすべてのバックエンドでサポートされます。

```lisp
(handler-bind ((error (lambda (c) (invoke-restart 'skip))))
  (multiple-value-list
   (with-simple-restart (skip "Skip the failing step.")
     (error "step failed")))) ; => (NIL T)
```
