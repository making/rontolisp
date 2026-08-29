# metal:shared-buffer

`(metal:shared-buffer ctx bytes)`

An `MTLBuffer` of `bytes` bytes in shared storage, whose contents the CPU rewrites -- what `metal:buffer` is not. A program that re-tessellates its geometry every frame allocates once here and `metal:upload`s per frame; how many it keeps in flight is its own business. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *scratch* (metal:shared-buffer *ctx* (* 4 36 1024)))
```
