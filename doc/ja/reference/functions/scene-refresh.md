# scene:refresh

`(scene:refresh v)`

ちょうど 1 フレームを描画します。ミューテータをまとめて呼んだ後の手順であり、静止したシーンに必要なのはこれだけです。カメラ操作は自分で再描画します。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:refresh *v*)
NIL
```
