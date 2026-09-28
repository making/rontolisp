# fli:pointerp

`(fli:pointerp object)`

`object` が外部ポインタなら真を返します。[`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) と [`fli:make-pointer`](fli-make-pointer.md) の答え、および `objc:invoke` やコールバックが受け取るポインタはすべて外部ポインタです。`ffi:` のポインタは含みません。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (fli:pointerp (objc:invoke (objc:data "abc") "bytes"))
T
```
