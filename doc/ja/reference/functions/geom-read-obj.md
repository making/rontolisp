# geom:read-obj

`(geom:read-obj path &key color label)`

Wavefront OBJ ファイル中のメッシュを [`geom:solid`](geom-polyhedron.md) として返します。`v` 行が頂点（4 つ目の `w` 成分は無視）、`f` 行が面で、そのトークンは `v`、`v/vt`、`v/vt/vn`、`v//vn` のいずれでも構いません。インデックスは正なら 1 始まり、負ならそこまでに読んだ頂点からの相対です。面の頂点数に制限はなく、OBJ の四角形は四角形のまま保たれ、他の面と同じく [`geom:mesh`](geom-mesh.md) がファン三角形分割します。

それ以外のレコード、すなわち `vn`、`vt`、`g`、`o`、`s`、`usemtl`、`mtllib`、コメントは読み飛ばします。**複数のオブジェクトを名指しするファイルも 1 つのソリッドとして読まれます**。それがソリッドというものだからです。単一の境界表現に色は 1 つ。マテリアル、テクスチャ座標、頂点法線は保持しません。`geom` にはそれらを収めるスロットがありません。

```console
CL-USER> (geom:read-obj "bunny.obj" :label "bunny")
#<GEOM:SOLID "bunny" 35947 vertices 69451 facets>
CL-USER> (geom:volume *)
7.700565237493941e-4
```

OBJ はそれ自身の単位を持ちます。上のバニーはメートル単位で 0.2 の大きさです。ですから枠に収めるのは `(scene:fit v)` の仕事であり、たいていはその横で `(scene:grid v :extent nil)` を指定したくなります。外側から見て時計回りに巻かれた面は `geom:volume` を加算ではなく減算させるので、体積が負ならファイルの巻き方が反転しているということです。

## バックエンド対応

ファイルシステムを持つすべてのバックエンド、すなわちインタプリタ、コンパイル済み `.class`/`.jar`、WASM Preview 1、WASI 0.3 コンポーネントで、4 つとも同じ答えを返します。数値はリーダー経由ではなく 1 文字ずつ走査するので、指数表記（`1.30e-2`）もどこでも浮動小数点数として読まれます。WASM バックエンドの [`read-from-string`](read-from-string.md) はこれをシンボルとして返します。
