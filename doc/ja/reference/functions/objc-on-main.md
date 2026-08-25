# objc:on-main

`(objc:on-main function)`

引数なしの関数をプロセスのメインスレッド (AppKit が属するスレッド) で呼び、その値を返します。関数がシグナルしたエラーは呼び出し側に再シグナルされます。すでにメインスレッド上にある関数はインラインで実行されるため、入れ子でもデッドロックしません。各 `objc:send` は自分で移動しますが、これは複数の send を 1 回の移動にまとめます。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:on-main (lambda () (+ 1 2)))
3
> (objc:on-main
    (lambda ()
      (let ((win (objc:send (objc:send "NSWindow" "alloc")
                            "initWithContentRect:styleMask:backing:defer:"
                            (list 0 0 400 200) 15 2 nil)))
        (objc:send win "setReleasedWhenClosed:" nil)
        (objc:send win "makeKeyAndOrderFront:" nil)
        win)))
#<objc NSWindow>
```
