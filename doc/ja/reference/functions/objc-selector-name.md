# objc:selector-name

`(objc:selector-name selector)`

セレクタの名前を返します。文字列は登録せずにそのまま返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:selector-name (objc:coerce-to-selector "setWidth:height:"))
"setWidth:height:"
```
