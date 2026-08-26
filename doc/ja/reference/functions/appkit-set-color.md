# appkit:set-color

`(appkit:set-color view color)`

パネルなら塗りつぶし色を、それ以外のコントロールなら文字色を設定し、色を返します。`appkit:set-text` の対になるもので、ビューが何であるかを尋ねる 1 つの動詞です。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (appkit:set-color *tile* (appkit:color 214 69 65))
#<objc _NSTaggedPointerColor>
CL-USER> (appkit:set-color *label* (appkit:color 25 60 210))
#<objc _NSTaggedPointerColor>
```
