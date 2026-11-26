# scene:clear

`(scene:clear v)`

Removes every solid from the viewer, releasing their GPU buffers. The grid, the axes and the camera are untouched. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:clear *v*)
NIL
```
