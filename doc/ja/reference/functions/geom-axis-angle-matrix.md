# geom:axis-angle-matrix

`(geom:axis-angle-matrix angle axis)`

`axis` まわりに `angle` ラジアン回転する 3x3 行列を、ロドリゲスの公式で作ります。軸は正規化されるので、キーワードでも任意の非零ベクトルでも構いません。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(mapcar (lambda (x) (round (* 1000 x)))
        (coerce (linalg:row (geom:axis-angle-matrix (/ 3.141592653589793 2) :z) 0) 'list))
; => (0 -1000 0)
```
