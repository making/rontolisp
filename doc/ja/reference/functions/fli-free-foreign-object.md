# fli:free-foreign-object

`(fli:free-foreign-object pointer)`

[`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) が確保したメモリを解放し、`nil` を返します。ヌルポインタは無視します。解放後のポインタは使えません。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:free-foreign-object (fli:allocate-foreign-object :type :int))
NIL
```
