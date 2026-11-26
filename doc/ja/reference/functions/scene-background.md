# scene:background

`(scene:background v rgba)`

フレーム開始時の色を `(r g b a)` のリストで設定します。最初の値は `scene:viewer` の `:background` が決めます。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:background *v* '(0.0 0.0 0.0 1.0))
NIL
```
