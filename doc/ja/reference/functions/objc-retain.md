# objc:retain

`(objc:retain pointer)`

`retain` を送り、`pointer` を返します。この参照はプログラムが解放するものです。ポインタ値のコレクタは明示的な retain を解放しないため、ポインタを捨てたあともオブジェクトを生かしておけます。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *o* (objc:alloc-init-object "NSObject"))
*O*
CL-USER> (objc:retain-count (objc:retain *o*))
2
```
