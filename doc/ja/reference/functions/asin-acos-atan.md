# asin acos atan

`(asin number)` `(acos number)` `(atan number)`

逆三角関数で、いずれも角度をラジアン単位の浮動小数点数で返します。`asin` は逆正弦、`acos` は逆余弦、`atan` は逆正接です。サポートされているのは引数 1 つの `atan` のみで、引数 2 つの `(atan y x)` 形式はありません。インタプリタと JVM バックエンドでのみ利用可能です（WASM では利用できません）。

```lisp
(atan 0) ; => 0.0
```
