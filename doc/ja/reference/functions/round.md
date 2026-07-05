# round

`(round number &optional divisor)`

`number`（除数を与えた場合は `number/divisor`）を最も近い整数に丸めます。銀行家の丸めを使うため、2 つの整数のちょうど中間の値は偶数の方に丸められます。通常の（単一値の）文脈では結果は商だけです。剰余は 2 番目の値であり、[`multiple-value-bind`](../macros/multiple-value-bind.md) などの多値コンシューマを通して観測できます。

```lisp
(round 3.5) ; => 4
```

```lisp
(round 2.5) ; => 2
```

```lisp
(multiple-value-bind (q r) (round 7 2)
  (list q r)) ; => (4 -1)
```
