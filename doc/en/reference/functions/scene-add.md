# scene:add

`(scene:add v &rest solids)`

Adds solids to the viewer's contents and answers the last one. A solid's mesh reaches the GPU the first time it is DRAWN, not here, and stays there: the per-frame cost of a solid is one 4x4 matrix and one draw call, never a triangle. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:add *v* (geom:box 100) (geom:sphere :radius 60))
#<instance GEOM:SOLID>
```
