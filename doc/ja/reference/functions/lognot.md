# lognot

`(lognot integer)`

`integer` のビット単位 NOT (1 の補数) を返します。`(- (+ integer 1))` と等価です。インタプリタと JVM では任意の大きさの整数に対して正確に演算します。WASM では符号付き 64 ビットの範囲内で正確に演算します。

```lisp
(lognot 5) ; => -6
```
