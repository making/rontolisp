# appkit:click

`(appkit:click button)`

ユーザーのクリックと同じようにボタンのアクションを実行します。人手なしにスクリプトからウィンドウを操作する方法です。`nil` を返します。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS のインタプリタ専用 (`java -jar` または `rontolisp` バイナリ) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:click *button*)
NIL
> (appkit:text *label*)
"clicked 1 time(s)"
```
