# logandc2

`(logandc2 integer1 integer2)`

`integer1` と `integer2` の補数のビット単位 AND、すなわち `(logand integer1 (lognot integer2))` です。どのバックエンドでも任意の大きさの整数に対して正確に演算します。

```lisp
(logandc2 12 10) ; => 4
```
