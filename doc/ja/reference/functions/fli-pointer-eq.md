# fli:pointer-eq

`(fli:pointer-eq pointer1 pointer2)`

二つの外部ポインタが型を問わず同じアドレスを持てば真を返します。同じアドレスを指す二つのポインタが `eq` とは限りません。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:pointer-eq (fli:make-pointer :address 4096 :type :int) (fli:make-pointer :address 4096))
T
```
