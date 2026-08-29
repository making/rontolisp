# metal:frame

`(metal:frame ctx fn)`

1 フレームを描画します。次のドローアブルを取得してクリアし、レンダーコマンドエンコーダを引数に `fn` を呼んでプログラムがパイプライン設定と描画を行い、提示してコミットします。`fn` はメインスレッドで走ります。レイヤに空きドローアブルがなければフレームはスキップされます。それがドロップフレームです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (metal:frame *ctx*
    (lambda (encoder)
      (objc:send encoder "setRenderPipelineState:" *solid*)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+triangle+ 0 3)))
```
