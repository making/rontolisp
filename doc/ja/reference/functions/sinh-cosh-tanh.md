# sinh cosh tanh

`(sinh number)` `(cosh number)` `(tanh number)`

双曲線関数で、それぞれ浮動小数点数を返します。`sinh` は双曲線正弦、`cosh` は双曲線余弦、`tanh` は双曲線正接です。インタプリタと JVM バックエンドでのみ利用可能です (WASM では利用できません)。

```lisp
(tanh 0) ; => 0.0
```
