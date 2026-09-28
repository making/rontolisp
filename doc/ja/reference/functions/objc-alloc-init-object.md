# objc:alloc-init-object

`(objc:alloc-init-object class)`

`(objc:invoke (objc:invoke class "alloc") "init")` と同じく、`class` (クラスポインタまたはクラス名) の新しいインスタンスを返します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:alloc-init-object "NSMutableArray")
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C08A20>
```
