# appkit:label

`(appkit:label window text &key (x 20) (y 20) (width 200) (height 24))`

そのテキストとフレームを持つ `NSTextField` ラベルをウィンドウの content view に追加して返します。座標系は AppKit のもので、原点はウィンドウの左下です。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
> (appkit:text *label*)
"no clicks yet"
```
