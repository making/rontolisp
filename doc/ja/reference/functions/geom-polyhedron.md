# geom:polyhedron

`(geom:polyhedron points facets &key color label)`

逃げ道となるコンストラクタ。アルゴリズムやファイル由来のメッシュを、生の点列とインデックスループで受け取ります。各面は `points` へのインデックスのリストで、外側から見て反時計回りに並べます。逆に巻くと `geom:volume` が加算ではなく減算します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (* 6 (geom:volume (geom:polyhedron '((0 0 0) (1 0 0) (0 1 0) (0 0 1))
                                          '((0 2 1) (0 1 3) (0 3 2) (1 2 3))))))
; => 1
```
