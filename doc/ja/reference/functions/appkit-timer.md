# appkit:timer

`(appkit:timer seconds fn)`

`seconds` ごとに引数なしで `fn` を呼ぶ繰り返し `NSTimer` を開始します。`fn` が `nil` を返すとタイマーは無効化されます。返り値はタイマーで、`(objc:send timer "invalidate")` でも止められます。`fn` は AppKit のイベントループの中、メインスレッドで走るので、ウィンドウを直接描き替えられます。`objc` の上に rontolisp で書かれ初回使用時に読み込まれる Cocoa ウィジェット層、`appkit` パッケージの一部です。macOS 専用 (`java -jar`、`rontolisp` バイナリ、またはコンパイル済み `.class` / `.jar`。`.wasm` は不可) で、ディスプレイが必要です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *ticks* 0)
CL-USER> (appkit:timer 1
    (lambda ()
      (setq *ticks* (+ *ticks* 1))
      (appkit:set-text *label* (format nil "~a s" *ticks*))
      (< *ticks* 10)))
#<objc __NSCFTimer>
```
