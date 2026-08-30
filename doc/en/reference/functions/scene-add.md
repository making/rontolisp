# scene:add

`(scene:add v &rest solids)`

Adds solids to the viewer's contents and answers the last one. Every argument is a solid or a LIST of solids, spliced in order, so a constructor that answers three of them -- [`geom:triad`](geom-triad.md) -- is handed over exactly like one that answers one. Anything else is refused here, naming it: a non-solid in the contents would otherwise surface a frame later, from inside the draw callback, as a `geom:user-data` dispatch failure. Nothing is added until every argument has been checked. A solid's mesh reaches the GPU the first time it is DRAWN, not here, and stays there: the per-frame cost of a solid is one 4x4 matrix and one draw call, never a triangle. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:add *v* (geom:box 100) (geom:triad :at (geom:vec3 0 0 0)))
#<instance GEOM:SOLID>
```
