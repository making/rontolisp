# scene:shading

`(scene:shading v mode)`

`:solid` は陰影付き三角形、`:wireframe` は稜線のみ、`:both` (既定) は三角形の上に稜線を描きます。どちらのメッシュもすでに GPU 上にあるので、モードの変更に費用はかかりません。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:shading *v* :wireframe)
NIL
```
