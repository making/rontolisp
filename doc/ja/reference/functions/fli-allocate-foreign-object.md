# fli:allocate-foreign-object

`(fli:allocate-foreign-object &key type pointer-type nelems initial-element initial-contents fill)`

FLI 型 `type` (または `pointer-type` つまり `(:pointer type)` の指す型) のオブジェクトを `nelems` 個 (既定値 1) ゼロで初期化した外部メモリに確保し、先頭を指す外部ポインタを返します。`fill` は全バイトを、`initial-element` は全オブジェクトを、シーケンスの `initial-contents` は先頭からのオブジェクトを設定します。メモリはどのターゲットでもプロセスのヒープで、[`fli:free-foreign-object`](fli-free-foreign-object.md) まで残ります。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (defvar *p* (fli:allocate-foreign-object :type :double :nelems 3 :initial-contents '(1 2.5 3)))
*P*
CL-USER> (fli:dereference *p* :index 1)
2.5
CL-USER> (fli:free-foreign-object *p*)
NIL
```
