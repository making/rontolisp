# geom:sphere

`(geom:sphere &key radius sides stacks color label)`

自身の原点を中心とする球。`stacks` 本の緯度帯を `sides` 本の経線に回転させて分割します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(round (geom:volume (geom:sphere :radius 50 :sides 32 :stacks 24)))
; => 518015
```
