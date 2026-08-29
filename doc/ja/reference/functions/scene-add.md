# scene:add

`(scene:add v &rest solids)`

ソリッドをビューアの内容に追加し、最後の 1 つを返します。ソリッドのメッシュが GPU に届くのはここではなく最初に描画されたときで、以後そこに留まります。1 ソリッドあたりのフレームごとのコストは 4x4 行列 1 つと描画コール 1 つだけで、三角形は 1 つも触りません。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:add *v* (geom:box 100) (geom:sphere :radius 60))
#<instance GEOM:SOLID>
```
