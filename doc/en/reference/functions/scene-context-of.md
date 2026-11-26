# scene:context-of

`(scene:context-of v)`

The `metal:context` the viewer draws through -- the escape hatch to the drawing surface, for a pass of your own on top of the scene. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (metal:set-clear-color (scene:context-of *v*) '(0.0 0.0 0.0 1.0))
NIL
```
