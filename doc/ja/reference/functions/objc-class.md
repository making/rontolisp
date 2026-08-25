# objc:class

`(objc:class "ClassName")`

その名前の Objective-C クラスを返します。読み込まれたどのフレームワークも宣言していなければシグナルします。クラスも他と同じ receiver で、クラスメソッドを送ったり `alloc` したりできます。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタと `rontolisp` ネイティブバイナリで動作し、コンパイル済み `.class` や `.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:class "NSString")
#<objc NSString>
> (objc:send (objc:class "NSString") "stringWithUTF8String:" "hi")
#<objc NSTaggedPointerString>
```

`objc:send` は receiver としてクラス*名*も受け付けるので、`(objc:send "NSString" ...)` に `objc:class` は不要です。
