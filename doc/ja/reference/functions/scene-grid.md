# scene:grid

`(scene:grid v &key extent spacing)`

地面グリッドを作り直します。z = 0 平面上、両方向に `extent` まで `spacing` ごとの線を、1 つの GPU バッファに入れて 1 回の描画コールで描きます。ビューアの初期値は `:extent 600 :spacing 50` です。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:grid *v* :extent 1200 :spacing 100)
NIL
```
