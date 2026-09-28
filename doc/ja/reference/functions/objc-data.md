# objc:data

`(objc:data buffer)`

バッファのバイト列を持つ `NSMutableData` です。`buffer` は任意ランクのパック float 配列 (single-float は 1 要素 4 バイト、double-float は 8 バイト)、パックされた `(unsigned-byte 8|16|32)` ベクタ、または文字列 (その UTF-8 バイト列) で、それ以外を渡すと `error` をシグナルします。バイト列は同じバッファに対して [`write-sequence`](write-sequence.md) が書くものとまったく同じ (リトルエンディアン、行優先) なので、`#f` 行列を GPU の頂点バッファへ渡すのに変換を挟む必要がありません。

メモリブロックはこの関数で Objective-C 側へ渡します。`[data bytes]` は `void *` 引数が求めるアドレスを返し、`[data mutableBytes]` は呼び出し先が書き込める領域になり、[`objc:bytes`](objc-bytes.md) が結果を読み戻します。`--native` 実行ファイルは `bfloat16` 配列と量子化行列を受け付けません。LispWorks のインターフェースにはない関数です。macOS 専用の `objc` パッケージの一部です。インタプリタ (`java -jar` または `rontolisp` ネイティブバイナリ)、コンパイル済み `.class` / `.jar`、Apple silicon の macOS 向け `--native` 実行ファイルで動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke (objc:data "hello") "length")
5
CL-USER> (objc:bytes (objc:data (make-array 2 :element-type 'single-float :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
CL-USER> (objc:data 42)
Error: objc:data expects a packed float array, a packed (unsigned-byte 8|16|32) vector or a string, got 42
```
