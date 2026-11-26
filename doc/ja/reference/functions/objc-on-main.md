# objc:on-main

`(objc:on-main function)`

引数なしの関数をプロセスのメインスレッド (AppKit が属するスレッド) で呼び、その値を返します。関数がシグナルしたエラーは呼び出し側で改めてシグナルされます。すでにメインスレッド上にある関数はインラインで実行されるため、入れ子でもデッドロックしません。各 `objc:invoke` は自分でメインスレッドへ移動しますが、この関数を使うと複数の送信の移動を 1 回にまとめられます。`--native` 実行ファイルではプログラムがもともとメインスレッドで動くので、ただの呼び出しになります。LispWorks のインターフェースにはない関数です。macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:on-main (lambda () (+ 1 2)))
3
CL-USER> (objc:on-main
    (lambda ()
      (let ((win (objc:invoke (objc:invoke "NSWindow" "alloc")
                              "initWithContentRect:styleMask:backing:defer:"
                              #(0 0 400 200) 15 2 nil)))
        (objc:invoke win "setReleasedWhenClosed:" nil)
        (objc:invoke win "makeKeyAndOrderFront:" nil)
        win)))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000100CCC5C0>
```
