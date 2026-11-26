# objc:trace-invoke

`(objc:trace-invoke method)`

`method` のすべての `objc:invoke` をトレースし、呼び出しと値を `*trace-output*` に出力します。`objc:untrace-invoke` で解除します。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:trace-invoke "length")
"length"
CL-USER> (objc:invoke *s* "length")
(objc:invoke #<Pointer: OBJC:OBJC-OBJECT-POINTER = #xB9D0A0E3E4E9A3A1> "length")
  => 11
11
```
