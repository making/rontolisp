# exact->inexact

`(exact->inexact z)`

`inexact` の R5RS での名前で、`z` に最も近い浮動小数点数を返します。`(scheme r5rs)` に属し、ここでは `import` で取り込めません。`import` のないファイルからだけ見えます。

```scheme
(exact->inexact 1/4) ; => 0.25
(exact->inexact 3) ; => 3.0
```
