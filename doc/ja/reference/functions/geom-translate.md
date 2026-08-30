# geom:translate

`(geom:translate node offset &key frame)`

ノードを `offset` だけ平行移動し、現在の姿勢に積み重ねます。`:frame` は `:local`（ノード自身の軸、既定）か `:parent`（接続先の軸）です。位置引数ではなく名前付きなので、`:frame :parent` と書かれた呼び出しはマニュアルなしで読めます。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((n (geom:make-node :axis :z :angle (/ 3.141592653589793 2))))
  (geom:translate n (geom:vec3 10 0 0) :frame :parent)
  (mapcar (lambda (x) (round (* 1000 x))) (coerce (geom:world-translation n) 'list)))
; => (10000 0 0)
```
