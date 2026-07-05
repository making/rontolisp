# multiple-value-call

`(multiple-value-call function values-form...)`

全ての `values-form` の全ての値を引数として `function` を呼び出します。関数が最初に評価され、続いてプロデューサが左から右に評価されます。各プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に構文的に認識されるため引数の個数は静的に決まり、フォームは直接の `funcall` に展開されます（CL からの逸脱: 特殊オペレータではなくマクロに分類されます）。`function` に渡す組み込み関数値は固定アリティのラッパーのままです（例えば `#'+` はちょうど 2 引数）。それ以外のアリティにはユーザ定義関数か `lambda` を使ってください。

```lisp
(multiple-value-call #'+ (values 1 2)) ; => 3
```

```lisp
(defun collect (&rest args) args)
(multiple-value-call #'collect 1 (values 2 3) (floor 9 4)) ; => (1 2 3 2 1)
```
