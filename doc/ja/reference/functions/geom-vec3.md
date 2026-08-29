# geom:vec3

`(geom:vec3 x y z)`

パックされた単精度浮動小数点数の3次元ベクトル。パッケージ全体が扱う座標型です。float32 は GPU の頂点バッファがそのまま持つ形式なので、`geom` の値は `objc:data` を通して変換なしで Metal に届き、`linalg` の変換は幅を保ちます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:vec3 1 2 3)
; => #f(1.0 2.0 3.0)
```
