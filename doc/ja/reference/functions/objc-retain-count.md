# objc:retain-count

`(objc:retain-count pointer)`

オブジェクトの `retainCount` を返します。タグ付きポインタ (短い `NSString` や小さい `NSNumber`) は意味のない最大値を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:retain-count (objc:invoke "NSObject" "new"))
1
```
