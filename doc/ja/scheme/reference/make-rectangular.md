# make-rectangular

`(make-rectangular real imaginary)`

虚部 `imaginary` の複素数 `real + imaginary*i` を返します。虚部が正確数の 0 なら実数そのものになり、`(make-rectangular 1 0)` は `1` です。浮動小数点数の 0 は複素数のまま残ります。

```scheme
(make-rectangular 1 2) ; => #C(1 2)
(make-rectangular 1 0) ; => 1
```
