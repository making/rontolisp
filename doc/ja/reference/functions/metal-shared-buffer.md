# metal:shared-buffer

`(metal:shared-buffer ctx bytes)`

共有ストレージ上の `bytes` バイトの `MTLBuffer` で、CPU が中身を書き換えます。`metal:buffer` にはできないことです。毎フレーム形状を再生成するプログラムはここで一度確保し、フレームごとに `metal:upload` します。いくつを同時に飛ばすかはプログラム側の判断です。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *scratch* (metal:shared-buffer *ctx* (* 4 36 1024)))
```
