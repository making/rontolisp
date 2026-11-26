# scene:snapshot

`(scene:snapshot v)`

オフスクリーンビューアの 1 フレームをピクセルとして返します。`width * height * 4` バイト、BGRA、行 0 が上です ([`metal:pixels`](metal-pixels.md) 参照)。フレームは他のフレームと同じく `scene:refresh` を通して描かれるので、返るものはウィンドウが表示するものそのものです。ウィンドウを持つビューアには読み戻すテクスチャがないためエラーを通知します。`scene` パッケージの一部で macOS 専用、`.wasm` は不可です。[`scene:offscreen`](scene-offscreen.md) を参照してください。

```console
CL-USER> (defvar *v* (scene:offscreen :width 64 :height 64))
CL-USER> (scene:grid *v* :extent nil)
CL-USER> (defvar *px* (scene:snapshot *v*))
CL-USER> (list (aref *px* 0) (aref *px* 1) (aref *px* 2))
(23 17 14)
```
