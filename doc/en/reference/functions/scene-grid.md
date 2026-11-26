# scene:grid

`(scene:grid v &key extent spacing)`

Rebuilds the ground grid: lines every `spacing` out to `extent` in both directions on z = 0, in one GPU buffer drawn with one call. A viewer starts with `:extent 600 :spacing 50`; `:extent nil` drops the grid altogether, the way `(scene:axes v nil)` drops the triads. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:grid *v* :extent 1200 :spacing 100)
NIL
```
