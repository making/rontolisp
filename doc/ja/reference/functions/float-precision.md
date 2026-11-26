# float-precision

`(float-precision float)`

`float` が保持する有効な2進桁数を返します(暗黙の先行ビットを含む): 正規化数では `53`、非正規化数ではそれより少なく、ゼロでは `0` です。無限大と NaN は、[`float-digits`](float-digits.md) がどの値にも返すのと同じ表現の桁数 `53` を返します。

```lisp
(float-precision 1.5) ; => 53
```

```lisp
(list (float-precision 0.0) (float-precision 4.9406564584124654d-324)) ; => (0 1)
```
