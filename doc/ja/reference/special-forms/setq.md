# setq

`(setq name value ...)`

変数 `name` に `value` を代入します。`value` は評価されますが `name` は評価されません。複数の `name value` の組を指定でき、それらは左から右へ代入されるため、後の `value` は前の代入結果を参照できます。最後の代入の値が返されます。`setq` は変数名前空間でのみ動作します(Lisp-2)。

```lisp
(let ((x 0)) (setq x 1 x (+ x 9)) x) ; => 10
```
