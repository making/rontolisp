# objc:bytes

`(objc:bytes data)`

`NSData` (または `NSData` と同じく `length` と `bytes` に応えるオブジェクト) の内容を、新しい `(unsigned-byte 8)` ベクタとして返します。[`objc:data`](objc-data.md) の逆方向です。セレクタに `objc:data` のブロックを書き込み先として渡し、書かれた内容を読み戻します。オブジェクトポインタ以外 (クラスを含む) を渡すと `error` をシグナルします。

LispWorks のインターフェースにはない関数です。macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:bytes (objc:data "hi"))
#(104 105)
CL-USER> (objc:bytes (objc:invoke (objc:string-to-ns-string "hello") "dataUsingEncoding:" 4))
#(104 101 108 108 111)
```
