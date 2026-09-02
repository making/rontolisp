# ftruncate

`(ftruncate number &optional divisor)`

[`truncate`](truncate.md) と同様に `number`（除数を与えた場合は `number/divisor`）をゼロ方向に丸めますが、主値は常に浮動小数点数になります -- CLHS は `ftruncate` を「商が FLOAT の `truncate`」と定義しています。2 番目の値（剰余）は `truncate` が返すものと同じです。

```lisp
(ftruncate -7 2) ; => -3.0
```

```lisp
(multiple-value-bind (q r) (ftruncate -7 2)
  (list q r)) ; => (-3.0 -1)
```
