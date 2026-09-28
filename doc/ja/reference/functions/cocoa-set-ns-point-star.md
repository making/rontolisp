# cocoa:set-ns-point*

`(cocoa:set-ns-point* point x y)`

`NSPoint` (`objc:invoke` が受け渡す形である要素が二つ以上のベクタ、またはその型の外部オブジェクト) を `#(x y)` に設定して返します。 `objc` と並ぶ macOS 専用の `cocoa` パッケージの一部です。[macOS GUI ガイド](../../guides/objc-appkit.md)を参照してください。

```console
CL-USER> (cocoa:set-ns-point* (make-array 2) 10 20)
#(10 20)
```
