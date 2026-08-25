# appkit:close

`(appkit:close window)`

ウィンドウを閉じ (隠し) ます。Lisp の値は有効なままです。`nil` を返します。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS のインタプリタ専用 (`java -jar` または `rontolisp` バイナリ) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:close *win*)
NIL
> (appkit:visible-p *win*)
NIL
```
