# objc:bytes

`(objc:bytes data)`

`NSData` の内容を、パックされた `(unsigned-byte 8)` ベクタとして返します。[`objc:data`](objc-data.md) の逆方向です。セレクタに `objc:data` のブロックを書き込み先として渡し、書かれた内容を読み戻します。

macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:bytes (objc:data "hi"))
#(104 105)
CL-USER> (length (objc:bytes (objc:send (objc:string "hello") "dataUsingEncoding:" 4)))
5
```
