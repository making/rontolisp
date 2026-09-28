# objc:make-autorelease-pool

`(objc:make-autorelease-pool)`

自動解放プールを作ります。`objc:release` で空にするまで (それ以降に作ったプールとともに) 現在のプールです。プールは Lisp 側で管理します。すべての送信はメインスレッド上のそれぞれ専用のプールで行われるため、本物の `NSAutoreleasePool` では二つの送信にまたがれません。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (let ((pool (objc:make-autorelease-pool)))
           (objc:autorelease (objc:alloc-init-object "NSObject"))
           (objc:release pool))
NIL
```
