# appkit:menu

`(appkit:menu items)`

項目が Lisp のクロージャである `NSMenu` を作ります。1 つのエントリは `(title handler)` で、3 つ目にキー同値を置けます — `(list "Quit" #'appkit:quit "q")`。キーワード `:separator` は区切り線です。ハンドラは引数を取らず、ボタンの `:on-click` とまったく同じく、AppKit のイベントループの中、メインスレッドで走ります。メニューは [`appkit:status-item`](appkit-status-item.md) にぶら下げます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (appkit:menu (list (list "Count" (lambda () (setq *n* (+ *n* 1))))
                     :separator
                     (list "Quit" #'appkit:quit "q")))
#<objc NSMenu>
```
