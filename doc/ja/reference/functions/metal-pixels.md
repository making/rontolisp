# metal:pixels

`(metal:pixels ctx)`

オフスクリーンコンテキストが最後に描いたフレームを、`width * height * 4` バイトのパックト `(unsigned-byte 8)` ベクタとして返します。並びはテクスチャそのものの順、すなわち **BGRA**、行 0 が上、詰めた配置です。RGBA へは変換しません -- 形式はレイヤの形式であり、どちらか一方の順序を知らねばならない読み手なら本当の順序を知るほうがよいからです。コンテキストがテクスチャではなくウィンドウに描く場合は、読み戻すものがないためエラーを通知します。`metal` パッケージの一部で macOS 専用、`.wasm` は不可です。[`metal:offscreen`](metal-offscreen.md) を参照してください。

```console
CL-USER> (defvar *ctx* (metal:offscreen :width 4 :height 4 :clear '(0.0 0.0 1.0 1.0)))
CL-USER> (metal:frame *ctx* (lambda (encoder) encoder))
CL-USER> (subseq (metal:pixels *ctx*) 0 4)
#(255 0 0 255)
```
