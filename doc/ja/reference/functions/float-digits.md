# float-digits

`(float-digits float)`

`float` の表現が使う2進桁数を返します(暗黙の先行ビットを含む)。浮動小数点数はすべて double なので、どの値でも `53` です。値が実際に保持する桁数 -- 非正規化数ではより少なく、ゼロでは0 -- は [`float-precision`](float-precision.md) が返します。

```lisp
(float-digits 1.5) ; => 53
```

```lisp
(float-digits 0.0) ; => 53
```
