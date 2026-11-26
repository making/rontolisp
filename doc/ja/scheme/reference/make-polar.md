# make-polar

`(make-polar magnitude angle)`

絶対値 `magnitude`・偏角 `angle` ラジアンの複素数、`magnitude*cos(angle) + magnitude*sin(angle)*i` を返します。

```scheme
(make-polar 1 0.7853981633974483) ; => #C(0.7071067811865476 0.7071067811865475)
```
