# fli:pointer-address

`(fli:pointer-address pointer)`

外部ポインタが持つアドレスを整数で返します。`ffi:` の関数がポインタとして受け取る値です。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:pointer-address (fli:make-pointer :address 4096))
4096
```
