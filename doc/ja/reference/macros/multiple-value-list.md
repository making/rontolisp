# multiple-value-list

`(multiple-value-list values-form)`

`values-form` の値をリストに集めます。プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に構文的に認識されます: リテラルの `(values ...)` 呼び出しと 2 値の組み込み関数（`floor`/`ceiling`/`round`/`truncate`、`gethash`）は全ての値を供給し、それ以外のフォームは単一の値を供給するため結果は 1 要素のリストになります。

```lisp
(multiple-value-list (floor 17 5)) ; => (3 2)
```

```lisp
(multiple-value-list (+ 1 2)) ; => (3)
```
