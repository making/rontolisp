# float-digits

`(float-digits float)`

`float` の仮数部の2進桁数を返します(暗黙の先行ビットを含む): 正規化数では常に `53`、非正規化数ではそれより少なく、ゼロでは `0` です。

```lisp
(float-digits 1.5) ; => 53
```

```lisp
(float-digits 0.0) ; => 0
```
