# scene:add

`(scene:add v &rest solids)`

ソリッドをビューアの内容に追加し、最後の 1 つを返します。各引数はソリッドまたはソリッドの**リスト**で、リストは順序を保って展開されます。したがって 3 つ返す [`geom:triad`](geom-triad.md) も 1 つ返すコンストラクタとまったく同じように渡せます。それ以外のものはこの場で名前を挙げて拒否されます。ソリッドでないものが内容に入ると、1 フレーム後に描画コールバックの内側から `geom:user-data` のディスパッチ失敗として現れるからです。全引数を検査し終えるまで 1 つも追加しません。ソリッドのメッシュが GPU に届くのはここではなく最初に描画されたときで、以後そこに留まります。1 ソリッドあたりのフレームごとのコストは 4x4 行列 1 つと描画コール 1 つだけで、三角形は 1 つも触りません。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:add *v* (geom:box 100) (geom:triad :at (geom:vec3 0 0 0)))
#<GEOM:SOLID "z" 73 vertices 73 facets>
```
