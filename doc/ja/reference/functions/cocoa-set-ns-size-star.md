# cocoa:set-ns-size*

`(cocoa:set-ns-size* size width height)`

`NSSize` (要素が二つ以上のベクタ) を `#(width height)` に設定して返します。 `objc` と並ぶ macOS 専用の `cocoa` パッケージの一部です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (cocoa:set-ns-size* (make-array 2) 640 480)
#(640 480)
```
