# metal:attach

`(metal:attach window &key clear scale depth)`

ウィンドウのコンテンツビューの裏地を `CAMetalLayer` に差し替え、他のすべての関数が受け取る `metal:context` を返します。`:clear` はフレーム開始時の `(r g b a)`、`:scale` はバッキングストア倍率 (Retina なら 2)、`:depth` は深度アタッチメントの要求です。凸でない形状にはこれが必要で、以降に作るパイプラインはすべてその形式を宣言します。デバイスはレイヤの `preferredDevice` から得るので、C のエントリポイントは介在しません。 `objc` の上に rontolisp で書かれ初回使用時に読み込まれる Metal 描画サーフェス、`metal` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *win* (appkit:window "metal" :width 640 :height 400 :dark t))
CL-USER> (defvar *ctx* (metal:attach *win* :clear '(0.05 0.06 0.09 1.0) :depth t))
CL-USER> (objc:send (objc:send (metal:device *ctx*) "name") "UTF8String")
"Apple M4 Max"
```
