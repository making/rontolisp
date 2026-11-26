# objc:objc-class-method-signature

`(objc:objc-class-method-signature class-spec method-name)`

クラス (名前、クラスポインタ、またはそのクラスを指すオブジェクト) のメソッドを三つの値で記述します。レシーバとセレクタを含む引数の型、結果の型、ランタイムの型エンコーディングです。同名のクラスメソッドよりインスタンスメソッドを優先し、どちらもなければ `nil` を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:objc-class-method-signature "NSString" "rangeOfString:")
(OBJC:OBJC-OBJECT-POINTER OBJC:SEL OBJC:OBJC-OBJECT-POINTER)
(:STRUCT COCOA:NS-RANGE)
"{_NSRange=QQ}24@0:8@16"
```
