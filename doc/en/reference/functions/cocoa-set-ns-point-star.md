# cocoa:set-ns-point*

`(cocoa:set-ns-point* point x y)`

Sets an `NSPoint` -- a vector of at least two elements, the form `objc:invoke` passes and answers one in -- to `#(x y)` and answers it. Part of the macOS-only `cocoa` package, beside `objc`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (cocoa:set-ns-point* (make-array 2) 10 20)
#(10 20)
```
