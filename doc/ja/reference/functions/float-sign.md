# float-sign

`(float-sign float &optional other)`

`float` の符号を浮動小数点数で返します: `1.0` または `-1.0`。`other` を与えると、その絶対値に `float` の符号を付けた値を返します。負のゼロは `-1.0` を返します。

```lisp
(float-sign -2.5) ; => -1.0
```

```lisp
(float-sign -2.5 3.0) ; => -3.0
```
