# metal:floats

`(metal:floats values)`

A packed single-float array of a list of numbers -- the representation `objc:data` turns into a Metal buffer's exact bytes. A `linalg` result and a `geom:mesh` are already such an array and need no conversion. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:floats '(1 2 3))
#f(1.0 2.0 3.0)
```
