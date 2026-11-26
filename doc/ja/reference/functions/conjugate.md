# conjugate

`(conjugate number)`

`number` の複素共役を返す。複素数なら虚部を反転させたもの、実数ならその数自身である。4バックエンドすべてで動作する。

```lisp
(conjugate #c(1 2)) ; => #C(1 -2)
```

```lisp
(conjugate 5) ; => 5
```
