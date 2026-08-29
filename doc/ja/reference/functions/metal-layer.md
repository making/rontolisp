# metal:layer

`(metal:layer ctx)`

ウィンドウのコンテンツビュー上の `CAMetalLayer`。フレームが提示される面であり、ドローアブルの出どころです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:send (metal:layer *ctx*) "drawableSize")
(1280.0 800.0)
```
