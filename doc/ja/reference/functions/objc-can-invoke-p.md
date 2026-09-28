# objc:can-invoke-p

`(objc:can-invoke-p class-or-object-pointer method)`

レシーバが `method` を持つかどうかを `t` か `nil` で返します。文字列はクラスを指し、そのクラスメソッドについて調べます。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:can-invoke-p *s* "length")
T
CL-USER> (objc:can-invoke-p *s* "frame")
NIL
```
