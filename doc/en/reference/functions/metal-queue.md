# metal:queue

`(metal:queue ctx)`

The `MTLCommandQueue` the context commits its command buffers to. One per context; `metal:frame` takes a buffer from it per frame. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:objectp (metal:queue *ctx*))
T
```
