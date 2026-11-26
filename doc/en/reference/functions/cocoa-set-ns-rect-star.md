# cocoa:set-ns-rect*

`(cocoa:set-ns-rect* rect x y width height)`

Sets an `NSRect` -- a vector of at least four elements, or a foreign object of the type -- to `#(x y width height)` and answers it. Part of the macOS-only `cocoa` package, beside `objc`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke (objc:invoke "NSValue" "valueWithRect:"
                                  (cocoa:set-ns-rect* (make-array 4) 0 0 640 480))
                     "rectValue")
#(0.0 0.0 640.0 480.0)
```
