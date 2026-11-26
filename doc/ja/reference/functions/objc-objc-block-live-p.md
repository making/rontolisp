# objc:objc-block-live-p

`(objc:objc-block-live-p block)`

`block` が `objc:free-objc-block` で解放されていなければ `t`、解放済みなら `nil` を返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (objc-block-live-p *add*)
T
MY-APP> (free-objc-block *add*)
NIL
MY-APP> (objc-block-live-p *add*)
NIL
```
