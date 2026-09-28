# objc:coerce-to-objc-class

`(objc:coerce-to-objc-class class)`

クラス名が指すクラスポインタを返します。クラスポインタはそのまま返します。ロード済みのどのイメージも定義しない名前は `error` をシグナルします。クラスごとに値は一つなので、同じクラスに対する二つの答えは `eq` です。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:coerce-to-objc-class "NSString")
#<Pointer: OBJC:OBJC-CLASS = #x00000001FA1263D8>
CL-USER> (eq (objc:coerce-to-objc-class "NSObject") (objc:invoke "NSObject" "class"))
T
```
