# metal:device

`(metal:device ctx)`

コンテキストが描画に使う `MTLDevice`。GPU そのものであり、Metal のあらゆる `new...` セレクタのレシーバです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:send (objc:send (metal:device *ctx*) "name") "UTF8String")
"Apple M4 Max"
```
