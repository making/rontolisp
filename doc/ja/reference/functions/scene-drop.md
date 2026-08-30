# scene:drop

`(scene:drop v &rest solids)`

ビューアからソリッドを取り除き、`geom:user-data` に保持していた GPU バッファを解放します。返り値は最後の 1 つで、モデルとしてはそのまま有効です。[`scene:add`](scene-add.md) と同じ形を取ります。各引数はソリッドまたはソリッドのリストで、リストは展開されるので、1 引数で入れたものは 1 引数で出せます。[`scene:clear`](scene-clear.md) はソリッドを 1 つも名指ししないので、対応する変更は要りません。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:drop *v* *hand*)
#<instance GEOM:SOLID>
```
