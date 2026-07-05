# multiple-value-call

`(multiple-value-call function values-form...)`

全ての `values-form` の全ての値を引数として `function` を呼び出します。関数が最初に評価され、続いてプロデューサが左から右に評価されます。各プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に認識され、結果が `(values ...)` 呼び出しであるユーザ関数も含まれます。その値は実行時に展開されるため、`multiple-value-call` を使うコンパイル済みプログラムには `apply` と同様にランタイム `eval` サポートが埋め込まれます（CL からの逸脱: 特殊オペレータではなくマクロに分類されます）。`function` に渡す組み込み関数値は合成されたラッパーです。本来可変長の演算子（`#'+`、`#'-`、`#'*`、`#'/`、`#'list`、`#'min`、`#'max`）は任意個の引数を受け取りますが、それ以外の複数引数の組み込みは固定アリティのラッパーのままです（例えば `#'cons` や比較チェインは 2 引数）。それ以外のアリティにはユーザ定義関数か `lambda` を使ってください。

```lisp
(multiple-value-call #'+ (values 1 2)) ; => 3
```

```lisp
(defun collect (&rest args) args)
(multiple-value-call #'collect 1 (values 2 3) (floor 9 4)) ; => (1 2 3 2 1)
```
