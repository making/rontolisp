# objc:objectp

`(objc:objectp value)`

値が Objective-C オブジェクトへの参照 (オブジェクトまたはクラス) かどうかを返します。どのマシンでも動作します。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

このパッケージが動作するどの環境でも、このような参照の型は `objc:object` です。`type-of` はこれを返し、`typep`、`typecase`、`defmethod` の特定子はこの名前を受け付けます。`structure-object` ではありません。

```console
CL-USER> (objc:objectp (objc:string "x"))
T
CL-USER> (objc:objectp "x")
NIL
CL-USER> (type-of (objc:string "x"))
OBJC:OBJECT
CL-USER> (typep (objc:string "x") 'objc:object)
T
```
