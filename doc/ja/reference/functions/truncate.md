# truncate

`(truncate number &optional divisor)`

`number`（除数を与えた場合は `number/divisor`）をゼロ方向に丸めて整数にし、小数部分を捨てます。通常の（単一値の）文脈では結果は商だけです。剰余は 2 番目の値であり、[`multiple-value-bind`](../macros/multiple-value-bind.md) などの多値コンシューマを通して観測できます。

```lisp
(truncate 3.7) ; => 3
```

```lisp
(multiple-value-bind (q r) (truncate -7 2)
  (list q r)) ; => (-3 -1)
```
