# geom:make-transform

`(geom:make-transform &key translation rotation rpy axis angle)`

剛体運動。並進3次元ベクトルと 3x3 回転から成ります。transform は「値」です。親も同一性もキャッシュも持たず、破壊的に変更されることもないので、同じものを何個のノードのローカル変換にしても構いません。回転は `:rotation`（行列）、`:rpy`（3要素リスト）、`:axis` と `:angle` のいずれかで与えます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:translation-of (geom:make-transform :translation (geom:vec3 1 2 3) :axis :z :angle 0.5))
; => #f(1.0 2.0 3.0)
```
