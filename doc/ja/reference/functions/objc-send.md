# objc:send

`(objc:send receiver "selector:with:" arg1 arg2)`

メッセージを送ります。receiver はオブジェクト、クラス、またはクラス名で、`nil` には `nil` を返します。引数と結果はランタイムから読んだセレクタ自身の型エンコーディングに従ってマーシャリングされます: オブジェクト引数にはオブジェクト、`nil`、または文字列 (`NSString` として送られる)、セレクタ引数にはその名前、`BOOL` には `t`/`nil`、`NSRect` などの構造体には数のリストを渡し、結果もリストで返ります。receiver が応答しないセレクタ、引数の個数違い、型に合わない引数は `error` になります。すべての send はメインスレッドで実行されます。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:send (objc:string "hello world") "length")
11
> (objc:send (objc:string "hello world") "rangeOfString:" "world")
(6 5)
> (objc:send "NSNumber" "numberWithDouble:" 2.5)
#<objc __NSCFNumber>
> (objc:send (objc:send "NSNumber" "numberWithDouble:" 2.5) "doubleValue")
2.5
```

宣言型と Lisp 側の形の対応表はガイドにあります。
