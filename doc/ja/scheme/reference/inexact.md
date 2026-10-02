# inexact

`(inexact z)`

`z` に最も近い浮動小数点数を返します。複素数の inexact は、各部の inexact の複素数です。

```scheme
(inexact 1/3) ; => 0.3333333333333333
(inexact 7) ; => 7.0
```
