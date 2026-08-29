# scene:animate

`(scene:animate v &optional hook)`

60 fps で描画し、各フレームの前に `hook` を 1 回呼びます。フックは姿勢を変える場所です。関節角、IK の 1 ステップ、動く目標など。動いたソリッドは再アップロードを必要としないので、レンダラにとっては無料です。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:animate *v* (lambda () (geom:turn *joint* 0.02 :z)))
NIL
```
