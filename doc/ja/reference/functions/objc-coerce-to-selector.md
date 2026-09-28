# objc:coerce-to-selector

`(objc:coerce-to-selector method)`

名前が指すセレクタを返し、必要なら登録します。セレクタはそのまま返します。名前ごとに値は一つです。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke-bool *s* "respondsToSelector:" (objc:coerce-to-selector "length"))
T
```
