# scene:background

`(scene:background v rgba)`

The colour a frame starts from, as an `(r g b a)` list. `scene:viewer`'s `:background` sets the first one. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:background *v* '(0.0 0.0 0.0 1.0))
NIL
```
