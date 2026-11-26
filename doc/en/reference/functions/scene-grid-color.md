# scene:grid-color

`(scene:grid-color v rgb)`

The colour of the ground grid, as a 3-vector of 0..1 components -- a `geom:vec3`. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:grid-color *v* (geom:vec3 0.2 0.5 0.4))
NIL
```
