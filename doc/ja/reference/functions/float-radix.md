# float-radix

`(float-radix float)`

浮動小数点表現の基数。ここの float はすべて2進倍精度なので、答えは常に `2` です。引数は1回だけ評価されます。

```lisp
(float-radix 1.0) ; => 2
```
