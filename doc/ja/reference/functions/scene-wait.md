# scene:wait

`(scene:wait v)`

ビューアのウィンドウが閉じられるまでブロックします。スクリプトが最後のフォームより長く生きるためのものです。REPL では不要です。 `metal` と `appkit` の上に rontolisp で書かれ初回使用時に読み込まれる `geom` ソリッドの 3D ビューア、`scene` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[ソリッドモデリングガイド](../../guides/solid-modeling.md)を参照してください。

```console
CL-USER> (scene:wait *v*)
```
