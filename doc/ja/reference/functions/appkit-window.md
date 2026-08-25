# appkit:window

`(appkit:window title &key (width 480) (height 300))`

その内容サイズの `NSWindow` を作り、タイトルを付け、中央に配置して表示し、キーウィンドウにしてアプリケーションをアクティブにします。値はウィンドウです。閉じても (赤いボタンや `appkit:close`) 隠れるだけで、プロセスは終了しません。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS のインタプリタ専用 (`java -jar` または `rontolisp` バイナリ) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *win* (appkit:window "hello" :width 420 :height 200))
> (appkit:visible-p *win*)
T
```
