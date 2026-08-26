# appkit:font

`(appkit:font size &key bold)`

そのポイントサイズのシステム `NSFont` を返します。`:bold` が真ならボールドです。`appkit:label` は `:size` と `:bold` から自分でフォントを作るので、これはウィジェット層が包んでいないビュー、つまり `setFont:` を持つ任意のビューのためのものです。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *heading* (appkit:font 19 :bold t))
CL-USER> (objc:send *text-view* "setFont:" *heading*)
```
