# scene:drop

`(scene:drop v s)`

ビューアからソリッドを 1 つ取り除き、`geom:user-data` に保持していた GPU バッファを解放します。返り値はそのソリッドで、モデルとしてはそのまま有効です。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:drop *v* *hand*)
#<instance GEOM:SOLID>
```
