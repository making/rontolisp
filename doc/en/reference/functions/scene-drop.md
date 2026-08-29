# scene:drop

`(scene:drop v s)`

Removes one solid from the viewer and releases the GPU buffers it kept in `geom:user-data`. Answers the solid, which is still a perfectly good model. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:drop *v* *hand*)
#<instance GEOM:SOLID>
```
