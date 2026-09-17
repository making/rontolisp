# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

双曲線関数で、それぞれ浮動小数点数を返します。`sinh` は双曲線正弦、`cosh` は双曲線余弦、`tanh` は双曲線正接です。3 つともすべてのバックエンドで動き、どれも fdlibm の値です（インタプリタと JVM では `StrictMath`、WASM では同じアルゴリズム）。そのため桁はどこでも一致します。`(sinh 0)` は正確に `0.0`、`(cosh 0)` は正確に `1.0` で、これはどのバックエンドでも同じです。

```lisp
(tanh 0) ; => 0.0
```
