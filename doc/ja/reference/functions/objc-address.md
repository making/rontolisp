# objc:address

`(objc:address object)`

オブジェクトのアドレスを整数で返します。ハッシュテーブルのキーにできる同一性です (同じオブジェクトへの 2 つの参照は同じアドレスを持ちます)。Objective-C オブジェクト以外にはシグナルします。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (integerp (objc:address (objc:string "x")))
T
```
