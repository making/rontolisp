# checkpoint:stage-float-bits

`(checkpoint:stage-float-bits stream count format dst &key (start 0))`

バイトストリーム `stream` の現在位置から、リトルエンディアンの 16 ビットワードを `count` 個、`format`（`:float16` か `:bfloat16`）のビットパターンとして読み、任意ランクのパックされた浮動小数点配列 `dst` にフラットインデックス `start` から行優先で拡張して書き込みます。`dst` を返します。

ワードは 100 万要素ずつ、パッケージがすべてのファイルのすべてのテンソルで再利用する 1 本の `(unsigned-byte 16)` バッファを通してステージングされ、各チャンクは [`rontolisp:widen-float-bits`](widen-float-bits.md) にそれぞれの `:start` オフセットで渡されます。パックされた整数ベクタはインタプリタと JVM で 1 要素 8 バイトを占めるので、テンソルを丸ごとステージングするとファイルサイズの 4 倍の一時領域を要します。ステージング済みのベクタではなくストリームを受け取るのは、それを間違えようがなくするためです。

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s data-start)
           (checkpoint:stage-float-bits s (* 2048 2048) :bfloat16
                                        (checkpoint:make-tensor '(2048 2048) 'single-float)))
#<packed single-float array (2048 2048)>
```

## バックエンドのサポート

[`rontolisp:widen-float-bits`](widen-float-bits.md) があり、ファイルシステムのあるすべてのバックエンド。現在はインタプリタとコンパイルされた `.class`/`.jar` です。
