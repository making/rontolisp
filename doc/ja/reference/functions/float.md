# float

`(float number &optional prototype)`

`number` を浮動小数点数 (double) に変換します。整数や有理数は最も近い浮動小数点数に変換されます。すでに浮動小数点数である値はそのまま返されます。省略可能な `prototype` は Common Lisp では浮動小数点数のサブタイプを選択しますが、rontolisp の浮動小数点数表現は 1 つなので、評価された後無視されます。

```lisp
(float 42) ; => 42.0
```

```lisp
(float 1/2) ; => 0.5
```
