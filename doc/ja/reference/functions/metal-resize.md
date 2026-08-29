# metal:resize

`(metal:resize ctx width height)`

レイヤを新しいコンテンツサイズ (ポイント単位) に追随させます。フレーム、ドローアブルサイズ (ポイント × バッキング倍率)、そして `metal:attach` が要求していれば新しい深度テクスチャです。サイズの変わったウィンドウは別のドローアブルであり、古いアタッチメントはもう一致しません。リサイズ可能なウィンドウを追う側はこれを呼んでからフレームを描きます。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:resize *ctx* 1024 640)
NIL
```
