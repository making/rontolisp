# metal:pipeline

`(metal:pipeline ctx lib vertex-name fragment-name &key blend)`

`lib` の 2 つの名前付き関数から作る `MTLRenderPipelineState` で、レイヤのピクセルフォーマットに描画します。`:blend` は加算合成 (one + one) にします。グローパスが必要とするものです。深度アタッチメントの形式は呼び出し側ではなくコンテキストに従います。パイプラインのアタッチメントは描画先のパスと一致しなければならないからです。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *solid* (metal:pipeline *ctx* *lib* "solid_vertex" "solid_fragment"))
CL-USER> (defvar *glow* (metal:pipeline *ctx* *lib* "sprite_vertex" "sprite_fragment" :blend t))
```
