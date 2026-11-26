# upgraded-complex-part-type

`(upgraded-complex-part-type type &optional environment)`

複素数型指定子に対する上方化された部分型を返す。`real` の下位型を指す指定子はそのまま返す（`'single-float` は `'single-float` のまま）。複合の実数指定子は先頭の名前を返す（`'(integer 0 10)` に対して `integer`）。それ以外はエラーを通知する。省略可能な `environment` は評価されるが使われない。4バックエンドすべてで動作する。

```lisp
(upgraded-complex-part-type 'integer) ; => INTEGER
```

```lisp
(upgraded-complex-part-type '(integer 0 10)) ; => INTEGER
```
