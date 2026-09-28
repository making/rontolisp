# fli:size-of

`(fli:size-of type-name)`

FLI 型のバイト数を C のレイアウト規則で返します。`:int` は 4、`:pointer` は 8、`(:c-array :int 4)` は 16 で、Foundation の構造体は LispWorks と同じく `cocoa:ns-rect` が 32、ほかの三つが 16 です。 `objc` と並ぶ macOS 専用の `fli` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (mapcar #'fli:size-of '(:char :int :double cocoa:ns-rect))
(1 4 8 32)
```
