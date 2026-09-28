# cocoa:set-ns-size*

`(cocoa:set-ns-size* size width height)`

Sets an `NSSize` -- a vector of at least two elements -- to `#(width height)` and answers it. Part of the macOS-only `cocoa` package, beside `objc`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (cocoa:set-ns-size* (make-array 2) 640 480)
#(640 480)
```
