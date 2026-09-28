# fli:null-pointer-p

`(fli:null-pointer-p pointer)`

外部ポインタのアドレスが 0 なら真を返します。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:null-pointer-p (fli:make-pointer :address 0))
T
```
