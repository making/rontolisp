# scene:shading

`(scene:shading v mode)`

`:solid` draws the lit triangles, `:wireframe` the edges alone, `:both` (the default) the edges over the triangles. Both meshes are on the GPU already, so the mode costs nothing to change. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:shading *v* :wireframe)
NIL
```
