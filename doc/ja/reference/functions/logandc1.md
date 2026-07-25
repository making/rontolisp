# logandc1

`(logandc1 integer1 integer2)`

`integer1` の補数と `integer2` のビット単位 AND、すなわち `(logand (lognot integer1) integer2)` です。インタプリタと JVM では任意の大きさの整数に対して正確に演算します。WASM では符号付き 64 ビットの範囲内で正確に演算します。

```lisp
(logandc1 12 10) ; => 2
```
