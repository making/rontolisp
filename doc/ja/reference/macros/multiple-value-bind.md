# multiple-value-bind

`(multiple-value-bind (var...) values-form body...)`

変数を `values-form` の値に束縛して本体を評価します。リテラルの [`values`](../functions/values.md) 呼び出しはその全ての値を、2 値の組み込み関数 [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md)（商と剰余）と [`gethash`](../functions/gethash.md)（値と present-p）は両方の値を供給します。結果が `(values ...)` 呼び出しであるユーザ関数の呼び出しもその全ての値を供給します: 余分な値は、呼び出された側の `values` が書き込み消費側が読み取る内部チャネルを通って関数境界を越えます。余った変数は nil に束縛され、余った値は評価されて捨てられます。Common Lisp からの逸脱: 末尾以外の位置で `values` を呼んでから通常の値を返すプロデューサは古い余剰値を残すことがあるため、`values` は結果位置で使ってください。

```lisp
(multiple-value-bind (q r) (floor 7 2)
  (list q r)) ; => (3 1)
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (NIL T)
```

```lisp
(defun div-mod (a b) (values (floor (/ a b)) (mod a b)))
(multiple-value-bind (q r) (div-mod 17 5)
  (list q r)) ; => (3 2)
```
