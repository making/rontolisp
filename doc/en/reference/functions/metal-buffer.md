# metal:buffer

`(metal:buffer ctx values)`

An `MTLBuffer` holding `values` -- a list, or a packed single-float array already. Copied once and never changed: this is the buffer a mesh that does not move belongs in. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *mesh* (metal:buffer *ctx* (geom:mesh (geom:box 100))))
```
