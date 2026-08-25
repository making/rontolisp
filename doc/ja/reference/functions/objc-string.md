# objc:string

`(objc:string "text")`

テキストを持つ `NSString` です。`objc:send` のオブジェクト引数に直接渡した Lisp 文字列も同じように変換されるので、これは文字列そのものを値として保持したい場合のためのものです。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタと `rontolisp` ネイティブバイナリで動作し、コンパイル済み `.class` や `.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:send (objc:string "hello") "length")
5
> (objc:send (objc:send (objc:string "hello") "uppercaseString") "UTF8String")
"HELLO"
```
