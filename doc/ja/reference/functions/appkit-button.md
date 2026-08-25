# appkit:button

`(appkit:button window title &key (x 20) (y 20) (width 120) (height 32) on-click)`

そのタイトルとフレームを持つプッシュボタンをウィンドウに追加して返します。`on-click` は引数なしの関数で、ボタンがクリックされた (または `appkit:click` された) ときにメインスレッドで実行され、GUI を呼び戻すことができます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS のインタプリタ専用 (`java -jar` または `rontolisp` バイナリ) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (defvar *n* 0)
> (defvar *button*
    (appkit:button *win* "Click me" :x 20 :y 40
      :on-click (lambda ()
                  (setq *n* (+ *n* 1))
                  (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*)))))
```
