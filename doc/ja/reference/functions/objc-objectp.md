# objc:objectp

`(objc:objectp value)`

値が Objective-C オブジェクトへの参照 (オブジェクトまたはクラス) かどうかを返します。どのマシンでも動作します。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタと `rontolisp` ネイティブバイナリで動作し、コンパイル済み `.class` や `.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:objectp (objc:string "x"))
T
> (objc:objectp "x")
NIL
```
