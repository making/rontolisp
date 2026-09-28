# objc:objc-class-name

`(objc:objc-class-name class)`

クラスの名前を返します。`objc:coerce-to-objc-class` の逆です。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:objc-class-name (objc:invoke *s* "class"))
"__NSCFString"
```
