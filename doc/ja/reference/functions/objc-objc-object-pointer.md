# objc:objc-object-pointer

`(objc:objc-object-pointer object)`

オブジェクトのポインタを返します。ポインタは自分自身を、`objc:standard-objc-object` はその Objective-C オブジェクトのポインタを、`objc:define-objc-class` で定義したクラス (`(find-class 'my-object)`) はその Objective-C クラスを返します。`objc:objc-object-pointer` はすべての Objective-C オブジェクト値の型名でもあります。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (eq (objc:objc-object-pointer *s*) *s*)
T
CL-USER> (typep *s* 'objc:objc-object-pointer)
T
```
