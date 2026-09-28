# objc:objc-object-from-pointer

`(objc:objc-object-from-pointer pointer)`

Objective-C オブジェクトに対応する Lisp オブジェクトを返します。Lisp で定義したクラスはまだないため `nil` を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:objc-object-from-pointer *s*)
NIL
```
