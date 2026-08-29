# metal:device

`(metal:device ctx)`

The `MTLDevice` the context draws with -- the GPU itself, and the receiver of every `new...` selector Metal has. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:send (objc:send (metal:device *ctx*) "name") "UTF8String")
"Apple M4 Max"
```
