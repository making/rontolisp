# geom:revolution

`(geom:revolution profile &key sides color label)`

xz 平面の x >= 0 側にある断面を z 軸まわりに回転させた立体。輪郭の端が軸に届いていない場合にだけ蓋が付きます。両端が同じ点である**閉じた**輪郭には端がないので、どちらにも蓋は付きません。`geom:sphere` も `geom:torus` もこれで作られています。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:revolution '((10 0 0) (10 0 20) (0 0 20)) :sides 64)))
; => 6273
```
