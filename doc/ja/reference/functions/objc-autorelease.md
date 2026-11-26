# objc:autorelease

`(objc:autorelease pointer)`

`objc:release` と同じ規則で参照を一つ手放し、最も内側の自動解放プールに渡します。プールは空になるときにそれを解放します。プールがなければポインタのコレクタに渡します。`pointer` を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:with-autorelease-pool ()
           (objc:ns-string-to-string (objc:autorelease (objc:string-to-ns-string "pooled"))))
"pooled"
```
