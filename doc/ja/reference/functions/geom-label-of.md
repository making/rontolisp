# geom:label-of

`(geom:label-of solid)`

ソリッドのラベル。呼び出し側が `:label` に渡した値（既定は `nil`）で、`setf` で設定できます。`geom` はこの値を解釈しません。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:label-of (geom:box 1 :label "lid"))
; => "lid"
```
