# asin acos atan

`(asin number)` `(acos number)` `(atan number)`

逆三角関数で、いずれも角度をラジアン単位の浮動小数点数で返します。`asin` は逆正弦、`acos` は逆余弦、`atan` は逆正接です。サポートされているのは引数 1 つの `atan` のみで、引数 2 つの `(atan y x)` 形式はありません。3 つともすべてのバックエンドで動きます。インタプリタと JVM は `Math.asin`/`Math.acos`/`Math.atan` を使い、WASM バックエンドは `atan` をソフトウェア近似(引数の折り返しと Taylor 級数、相対誤差 ~1e-15)で計算し、`asin`/`acos` をそこから導出するため、WASM の結果は最下位の 1〜2 桁がインタプリタ・JVM と異なることがあります。`(asin 1)` は正確に `pi/2`、`(acos 1)` は正確に `0.0` で、`asin`/`acos` の引数が `[-1, 1]` の外なら `NaN` を返します。

```lisp
(atan 0) ; => 0.0
```
