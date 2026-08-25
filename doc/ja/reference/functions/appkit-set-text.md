# appkit:set-text

`(appkit:set-text view text)`

ボタンならタイトルを、それ以外のコントロールなら string value を設定し、テキストを返します。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:set-text *label* "hello")
"hello"
> (appkit:set-text *button* "Again")
"Again"
```
