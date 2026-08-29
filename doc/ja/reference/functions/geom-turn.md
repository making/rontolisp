# geom:turn

`(geom:turn node angle axis &key frame)`

ノードを `axis` まわりに `angle` ラジアン回転させます。現在の姿勢に積み重ねます。`:frame` は `geom:move` と同じく `:local`（既定）か `:parent` です。アニメーションループでは、姿勢を直接設定してドリフトしない `geom:place` を使ってください。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((n (geom:make-node)))
  (geom:turn n (/ 3.141592653589793 2) :z)
  (mapcar (lambda (x) (round (* 1000 x))) (coerce (linalg:row (geom:world-rotation n) 0) 'list)))
; => (0 -1000 0)
```
