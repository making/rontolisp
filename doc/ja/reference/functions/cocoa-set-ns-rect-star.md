# cocoa:set-ns-rect*

`(cocoa:set-ns-rect* rect x y width height)`

`NSRect` (要素が四つ以上のベクタ、またはその型の外部オブジェクト) を `#(x y width height)` に設定して返します。 `objc` と並ぶ macOS 専用の `cocoa` パッケージの一部です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (objc:invoke (objc:invoke "NSValue" "valueWithRect:"
                                  (cocoa:set-ns-rect* (make-array 4) 0 0 640 480))
                     "rectValue")
#(0.0 0.0 640.0 480.0)
```
