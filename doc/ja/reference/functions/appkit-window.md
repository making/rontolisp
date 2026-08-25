# appkit:window

`(appkit:window title &key (width 480) (height 300) background dark)`

その内容サイズの `NSWindow` を作り、タイトルを付け、中央に配置して表示し、キーウィンドウにしてアプリケーションをアクティブにします。値はウィンドウです。閉じても (赤いボタンや `appkit:close`) 隠れるだけで、プロセスは終了しません。`:background` はウィンドウ自体の `NSColor`、`:dark` はダーク外観の指定で、タイトルバーもそれに従います。指定しないと、暗いウィンドウの上に明るい帯が乗り、そこに信号機ボタンが並んでしまいます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *win* (appkit:window "hello" :width 420 :height 200))
> (appkit:visible-p *win*)
T
> (appkit:window "night" :background (appkit:color 26 29 38) :dark t)
#<objc NSWindow>
```
