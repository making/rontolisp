# ffloor

`(ffloor number &optional divisor)`

[`floor`](floor.md) と同様に `number`（除数を与えた場合は `number/divisor`）を負の無限大方向に丸めますが、主値は常に浮動小数点数になります -- CLHS は `ffloor` を「商が FLOAT の `floor`」と定義しています。2 番目の値（剰余）は `floor` が返すものと同じです。

```lisp
(ffloor 7 2) ; => 3.0
```

```lisp
(multiple-value-bind (q r) (ffloor 7 2)
  (list q r)) ; => (3.0 1)
```
