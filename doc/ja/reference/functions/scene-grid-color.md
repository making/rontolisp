# scene:grid-color

`(scene:grid-color v rgb)`

地面グリッドの色を、0..1 の成分を持つ 3 要素ベクトル (`geom:vec3`) で設定します。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:grid-color *v* (geom:vec3 0.2 0.5 0.4))
NIL
```
