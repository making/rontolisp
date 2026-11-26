# fceiling

`(fceiling number &optional divisor)`

[`ceiling`](ceiling.md) と同様に `number`（除数を与えた場合は `number/divisor`）を正の無限大方向に丸めますが、主値は常に浮動小数点数になります -- CLHS は `fceiling` を「商が FLOAT の `ceiling`」と定義しています。2 番目の値（剰余）は `ceiling` が返すものと同じです。

```lisp
(fceiling 7 2) ; => 4.0
```

```lisp
(multiple-value-bind (q r) (fceiling 7 2)
  (list q r)) ; => (4.0 -1)
```
