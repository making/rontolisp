# magnitude

`(magnitude z)`

`z` の絶対値を返します。複素数では `sqrt(実部^2 + 虚部^2)`、実数では通常の絶対値です。

```scheme
(magnitude #C(3 4)) ; => 5.0
(magnitude 3) ; => 3
```
