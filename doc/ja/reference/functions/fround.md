# fround

`(fround number &optional divisor)`

[`round`](round.md) と同様に `number`（除数を与えた場合は `number/divisor`）を最近接の整数に丸めます（同点は偶数側）が、主値は常に浮動小数点数になります -- CLHS は `fround` を「商が FLOAT の `round`」と定義しています。2 番目の値（剰余）は `round` が返すものと同じです。

```lisp
(fround 7 2) ; => 4.0
```

```lisp
(multiple-value-bind (q r) (fround 7 2)
  (list q r)) ; => (4.0 -1)
```
