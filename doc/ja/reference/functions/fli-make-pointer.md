# fli:make-pointer

`(fli:make-pointer &key address type pointer-type)`

`address` を指す、FLI 型 `type` (または `pointer-type` の指す型、既定値 `:void`) の外部ポインタを返します。メモリは確保しません。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:make-pointer :address 4096 :type :int)
#<Pointer to type :INT = #x0000000000001000>
```
