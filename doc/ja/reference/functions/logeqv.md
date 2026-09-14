# logeqv

`(logeqv &rest integers)`

`integers` のビット単位等価。2引数の `(lognot (logxor x y))` の左畳み込みです。`(logeqv)` は `-1`、`(logeqv x)` は `x` を返します。各引数は左から右へちょうど1回ずつ評価され、全バックエンドで任意精度の整数に対して正確です。

```lisp
(logeqv 12 10) ; => -7
```
