# objc:string-to-ns-string

`(objc:string-to-ns-string string &optional autoreleasep)`

`string` を保持する `NSString` を返します。プログラムが所有するので `objc:release` で手放します。`autoreleasep` を与えると現在の自動解放プールに渡します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke (objc:string-to-ns-string "hi") "length")
2
```
