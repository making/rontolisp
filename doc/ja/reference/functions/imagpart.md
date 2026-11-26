# imagpart

`(imagpart number)`

`number` の虚部を返す。複素数ならその虚部、実数ならゼロである。整数のゼロ、浮動小数点数引数に対しては浮動小数点数のゼロを返す。4バックエンドすべてで動作する。

```lisp
(imagpart #c(1 2)) ; => 2
```

```lisp
(imagpart 5.5) ; => 0.0
```
