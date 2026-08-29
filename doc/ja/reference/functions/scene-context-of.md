# scene:context-of

`(scene:context-of v)`

ビューアが描画に使っている `metal:context`。シーンの上に独自のパスを重ねるための、描画サーフェスへの抜け道です。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (metal:set-clear-color (scene:context-of *v*) '(0.0 0.0 0.0 1.0))
NIL
```
