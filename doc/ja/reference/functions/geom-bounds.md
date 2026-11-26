# geom:bounds

`(geom:bounds solid-or-list)`

ソリッド、またはソリッドのリストの軸並行バウンディングボックス。ワールド座標なのでシーングラフに追従します。`geom:lower-of` / `geom:upper-of` / `geom:bounds-center` / `geom:bounds-extent` で読み出します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(coerce (geom:bounds-extent (geom:bounds (geom:box '(100 200 300)))) 'list)
; => (100.0 200.0 300.0)
```
