# multiple-value-bind

`(multiple-value-bind (var...) values-form body...)`

変数を `values-form` の値に束縛して本体を評価します。プロデューサは構文的に認識されます: リテラルの [`values`](../functions/values.md) 呼び出しはその全ての値を、2 値の組み込み関数 [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md)（商と剰余）と [`gethash`](../functions/gethash.md)（値と present-p）は両方の値を供給します。それ以外のフォーム -- 本体が `(values ...)` で終わる関数の呼び出しを含む -- は単一の値を供給します。余った変数は nil に束縛され、余った値は評価されて捨てられます。

```lisp
(multiple-value-bind (q r) (floor 7 2)
  (list q r)) ; => (3 1)
```

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) nil)
  (multiple-value-bind (v present-p) (gethash 'a h)
    (list v present-p))) ; => (nil t)
```
