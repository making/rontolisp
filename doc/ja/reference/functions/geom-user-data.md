# geom:user-data

`(geom:user-data solid)`

利用側が独自の状態を置くスロット。`setf` で設定できます。レンダラは GPU バッファをここに保持します。記述対象のメッシュと同じ場所にあるので、`geom:detach` で孤児になることがありません。`geom:nscale` はキャッシュと一緒にこのスロットも消します。 [ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照。

```lisp
(let ((s (geom:box 1)))
  (setf (geom:user-data s) (list :buffer 42))
  (geom:user-data s))
; => (:BUFFER 42)
```
