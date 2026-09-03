# checkpoint:stage-float32

`(checkpoint:stage-float32 stream dst)`

バイトストリーム `stream` の現在位置から、F32 テンソル（任意ランクのパックされた単精度配列 `dst` のバイト列、リトルエンディアン）を 1 回の `read-sequence` で読み込みます。`dst` を返します。変換の要らない幅のための、[`checkpoint:stage-float-bits`](checkpoint-stage-float-bits.md) の対です。

```console
CL-USER> (with-open-file (s "model.safetensors" :element-type '(unsigned-byte 8))
           (checkpoint:skip-bytes s data-start)
           (checkpoint:stage-float32 s (checkpoint:make-tensor 2048 'single-float)))
#f(0.0234375 -0.0078125 ...)
```
