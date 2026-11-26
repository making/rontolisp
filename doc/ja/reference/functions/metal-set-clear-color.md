# metal:set-clear-color

`(metal:set-clear-color ctx rgba)`

フレーム開始時の色を `(r g b a)` のリストで設定します。最初の値は `metal:attach` が受け取り、この関数が後から変更します。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:set-clear-color *ctx* '(0.0 0.0 0.0 1.0))
NIL
```
