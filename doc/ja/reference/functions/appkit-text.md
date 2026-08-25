# appkit:text

`(appkit:text view)`

ボタンならタイトルを、それ以外のコントロールなら string value を Lisp 文字列で返します。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:text *label*)
"hello"
```
