# asinh acosh atanh

`(asinh number)` `(acosh number)` `(atanh number)`

逆双曲線関数です。`asinh` はすべての実数を受け付けます。`acosh` の実数定義域は `[1, +inf)`、`atanh` は开区間 `(-1, 1)` であり、そで、その外の実数引数は `sqrt` が負の数を複素平面に送るのと同じく複素平面へ跨ぎます: `(acosh 0)` の虚部は pi/2、`(atanh 2)` の実部は ln 3 / 2 で虚部は pi/2 です。複素数の引数に対しては複素平面で答えます: `acosh` は ANSI の形 `2·log(sqrt((z+1)/2) + sqrt((z-1)/2))`、`atanh` は主値の対数の差 `(log(1+z) - log(1-z)) / 2` を取ります。`java.lang.Math` に逆双曲線関数はないため、3 つとも fdlibm の `log1p`・`log`・`hypot` 上の手組みの式 1 つずつで、どのバックエンドも同じ式を同じ順で評価するため、ビットはどこでも一致します。`(asinh 0)`、`(acosh 1)`、`(atanh 0)` はすべてのバックエンドで正確に `0.0` です。

```lisp
(asinh 0) ; => 0.0
(acosh 1) ; => 0.0
(atanh 0) ; => 0.0
```
