# objc:objc-block-pointer

`(objc:objc-block-pointer block)`

`block` のリテラルのアドレス (整数) を返します。`objc:free-objc-block` で解放した後は `nil` です。必要になることはまれです。`objc:objc-block` はブロックを受け取る場所にそのまま渡せます。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
MY-APP> (integerp (objc-block-pointer *add*))
T
```
