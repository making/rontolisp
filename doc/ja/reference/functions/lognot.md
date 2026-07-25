# lognot

`(lognot integer)`

`integer` のビット単位 NOT (1 の補数) を返します。`(- (+ integer 1))` と等価です。どのバックエンドでも任意の大きさの整数に対して正確に演算します。

```lisp
(lognot 5) ; => -6
```
