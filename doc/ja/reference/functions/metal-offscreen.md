# metal:offscreen

`(metal:offscreen &key width height clear depth)`

ウィンドウをまったく持たない `metal:context` です。フレームは専用のテクスチャに描かれ、`metal:pixels` で読み戻せます。`:width` と `:height` はピクセル単位 (画面がなければバッキング倍率も存在しません)、`:clear` はフレーム開始時の `(r g b a)`、`:depth` は深度アタッチメントの要求です。それ以外 -- `metal:library`、`metal:pipeline`、`metal:depth-state`、`metal:buffer`、`metal:uniform`、`metal:frame` -- はレイヤ付きコンテキストが受け取るのと同じ関数です。そこが要点で、ここで描かれるものはウィンドウが描くものそのものであり、似て非なる第二の経路ではありません。デバイスは `metal:attach` と同じく使い捨ての `CAMetalLayer` の `preferredDevice` から得られ、このプロパティが答えるのにディスプレイは不要です。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可)。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *ctx* (metal:offscreen :width 256 :height 192 :depth t))
CL-USER> (length (metal:pixels *ctx*))
196608
```
