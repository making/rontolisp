# scene:drop

`(scene:drop v &rest solids)`

Removes solids from the viewer and releases the GPU buffers they kept in `geom:user-data`. Answers the last one, which is still a perfectly good model. It takes the shape [`scene:add`](scene-add.md) takes -- a solid or a LIST of solids per argument, spliced -- so what went in as one argument comes back out as one argument. [`scene:clear`](scene-clear.md) names no solid at all and needs no equivalent. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:drop *v* *hand*)
#<instance GEOM:SOLID>
```
