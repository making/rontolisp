# objc:ns-string-to-string

`(objc:ns-string-to-string ns-string &optional preserve-line-terminators)`

`NSString` の文字を Lisp 文字列で返します。`preserve-line-terminators` が偽なら、CR LF の組と単独の CR をそれぞれ改行にします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:ns-string-to-string *s*)
"hello world"
```
