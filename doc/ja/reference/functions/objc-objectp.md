# objc:objectp

`(objc:objectp value)`

値が `objc:objc-object-pointer` (オブジェクトまたはクラス。`objc:objc-class` はそのサブタイプ) かどうかを返します。セレクタ、文字列、`objc:standard-objc-object` (そのポインタは `objc:objc-object-pointer` が返します) は該当しません。ランタイムに触れないので、どのマシンでも動作します。LispWorks のインターフェースにはない関数です。macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:objectp (objc:string-to-ns-string "x"))
T
CL-USER> (objc:objectp (objc:coerce-to-objc-class "NSString"))
T
CL-USER> (objc:objectp (objc:coerce-to-selector "length"))
NIL
CL-USER> (objc:objectp "x")
NIL
```
