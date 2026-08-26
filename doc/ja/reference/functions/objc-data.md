# objc:data

`(objc:data buffer)`

バッファのバイト列を持つ `NSMutableData` です。`buffer` は任意ランクのパック float 配列 (single-float は 1 要素 4 バイト、double-float は 8 バイト)、パックされた `(unsigned-byte 8|16|32)` ベクタ、または文字列 (その UTF-8 バイト列) です。バイト列は同じバッファに対して [`write-sequence`](write-sequence.md) が書くものとまったく同じ (リトルエンディアン、行優先) なので、`#f` 行列を GPU の頂点バッファへ渡すのに変換を挟む必要がありません。

メモリブロックが Objective-C 側へ渡る道はこれです。`[data bytes]` は `void *` 引数が求めるアドレスを返し、`[data mutableBytes]` は呼び出し先が書き込める領域になり、[`objc:bytes`](objc-bytes.md) が結果を読み戻します。macOS 専用の `objc` パッケージの一部です。`java -jar` のインタプリタ、`rontolisp` ネイティブバイナリ、コンパイル済み `.class` / `.jar` で動作し、`.wasm` では使えません。ランタイムのないマシンでは `error` をシグナルします。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
> (objc:send (objc:data "hello") "length")
5
> (objc:bytes (objc:data (make-array 2 :element-type 'single-float :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
```
