# appkit:color

`(appkit:color r g b &optional (alpha 1.0))`

赤・緑・青の各成分 (0-255) とアルファ (0.0 が透明、1.0 が不透明) から `NSColor` を返します。このパッケージで色を取る引数はすべてこれを受け取ります。ウィンドウの `:background`、パネルの `:fill`、ラベルの `:color`、`appkit:set-color` です。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *amber* (appkit:color 255 176 74))
> (appkit:label *win* "007" :x 20 :y 20 :width 96 :height 44 :color *amber*)
#<objc RontoLispAppKitLabel>
```
