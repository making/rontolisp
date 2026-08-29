# geom:inverse-transform-point

`(geom:inverse-transform-point transform point)`

逆変換を作らずに点を戻します。並進を引いてから転置行列で回転します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:inverse-transform-point (geom:make-transform :translation (geom:vec3 0 0 10)) (geom:vec3 1 2 13))
; => #f(1.0 2.0 3.0)
```
