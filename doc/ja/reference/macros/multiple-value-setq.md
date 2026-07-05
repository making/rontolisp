# multiple-value-setq

`(multiple-value-setq (var...) values-form)`

`values-form` の値を既存の変数へ `setq` で代入し、第 1 値を返します。プロデューサは [`multiple-value-bind`](multiple-value-bind.md) と同様に認識されます（リテラルの [`values`](../functions/values.md) 呼び出し、2 値を返す組み込み [`floor`](../functions/floor.md)/[`ceiling`](../functions/ceiling.md)/[`round`](../functions/round.md)/[`truncate`](../functions/truncate.md) と [`gethash`](../functions/gethash.md)）。余った変数には nil が代入されます。

```lisp
(let (a b)
  (multiple-value-setq (a b) (floor 17 5))
  (list a b)) ; => (3 2)
```

```lisp
(let (a b)
  (multiple-value-setq (a b) (values 1 2))) ; => 1
```
