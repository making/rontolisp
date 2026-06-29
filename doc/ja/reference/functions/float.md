# float

`(float number)`

`number` を浮動小数点数 (double) に変換します。整数や有理数は最も近い浮動小数点数に変換されます。すでに浮動小数点数である値はそのまま返されます。

```lisp
(float 42) ; => 42.0
```

```lisp
(float 1/2) ; => 0.5
```
