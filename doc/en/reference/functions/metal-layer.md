# metal:layer

`(metal:layer ctx)`

The `CAMetalLayer` on the window's content view: the surface frames are presented to, and where the drawable comes from. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:send (metal:layer *ctx*) "drawableSize")
(1280.0 800.0)
```
