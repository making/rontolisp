# scene:wait

`(scene:wait v)`

Blocks until the viewer's window is closed, so a script outlives its last form. A REPL needs none of this. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:wait *v*)
```
