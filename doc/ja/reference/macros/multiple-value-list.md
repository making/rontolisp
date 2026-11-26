# multiple-value-list

`(multiple-value-list values-form)`

`values-form` の値をリストに集めます。プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に認識されます: リテラルの `(values ...)` 呼び出し、多値の組み込み関数（`floor`/`ceiling`/`round`/`truncate`、`gethash`、`parse-integer`、`values-list`）、および結果が `(values ...)` 呼び出しであるユーザ関数の呼び出しは全ての値を供給します。それ以外のプロデューサ（変数、リテラル、通常の値を返す関数）は単一の値を供給するため、結果は 1 要素のリストになります。

```lisp
(multiple-value-list (floor 17 5)) ; => (3 2)
```

```lisp
(multiple-value-list (+ 1 2)) ; => (3)
```

```lisp
(defun two () (values 1 2))
(multiple-value-list (two)) ; => (1 2)
```
