# geom:rpy-matrix

`(geom:rpy-matrix roll pitch yaw)`

x まわりのロール、y まわりのピッチ、z まわりのヨーを順に合成した 3x3 回転行列。`geom:make-transform` と `geom:place` が `:rpy` として受け取る規約です。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(mapcar (lambda (x) (round (* 1000 x)))
        (coerce (linalg:row (geom:rpy-matrix 0 0 (/ 3.141592653589793 2)) 0) 'list))
; => (0 -1000 0)
```
