# objc:release

`(objc:release pointer)`

ポインタが保持する参照を一つ手放し (明示的な retain を先に、なければ受け取ったときの参照を)、`release` を送ります。何も保持していないポインタは過剰解放せずにシグナルするため、手動管理のコードがコレクタの解放と二重に解放することはありません。`objc:make-autorelease-pool` のプールを渡すとプールを空にします。 macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:release *o*)
NIL
CL-USER> (objc:release *o*)
NIL
CL-USER> (objc:release *o*)
error: objc:release: #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010> holds no reference this program can give up
```
