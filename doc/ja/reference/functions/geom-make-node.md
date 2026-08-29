# geom:make-node

`(geom:make-node &key transform translation rotation rpy axis angle parent)`

シーングラフのノード。ローカル変換を「持つ」もので、ソリッドもカメラ注視点も素の関節フレームも、余分なスロットなしにすべてノードとして表せます。`:parent` を渡すとその場で接続され、残りのキーワードは `geom:make-transform` と同じ形でローカル変換を作ります。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(geom:world-translation (geom:make-node :translation (geom:vec3 0 0 100)))
; => #f(0.0 0.0 100.0)
```
