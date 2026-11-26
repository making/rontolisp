# metal:upload

`(metal:upload buffer values)`

Copies `values` into `buffer`, which must be at least as long. The memcpy is `NSData`'s `getBytes:length:` into the buffer's `contents`. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:upload *scratch* (metal:floats '(0.0 1.0 0.0)))
```
