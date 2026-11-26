# metal:depth-state

`(metal:depth-state ctx &key writes compare)`

`MTLDepthStencilState`。パイプラインが深度アタッチメントをどう使うかです。`:writes nil` はグローパス用で、ソリッドパスが書いた深度を読むので機械の背後のスプライトは隠れますが、自身は書かないのでスプライト同士は遮蔽しません。`:compare` の既定は `metal:+compare-less+` です。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *depth* (metal:depth-state *ctx*))
CL-USER> (defvar *read-only* (metal:depth-state *ctx* :writes nil))
```
