# ceiling

`(ceiling number &optional divisor)`

`number`（除数を与えた場合は `number/divisor`）を正の無限大方向に丸めて整数にします。通常の（単一値の）文脈では結果は商だけです。剰余は 2 番目の値であり、[`multiple-value-bind`](../macros/multiple-value-bind.md) などの多値コンシューマを通して観測できます。

```lisp
(ceiling 3.2) ; => 4
```

```lisp
(multiple-value-bind (q r) (ceiling 7 2)
  (list q r)) ; => (4 -1)
```
