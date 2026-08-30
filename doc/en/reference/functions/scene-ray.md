# scene:ray

`(scene:ray v x y)`

The world-space eye ray through a point of the view: `(origin direction)`, two 3-vectors, the direction a unit vector. `X` and `Y` are view coordinates in points -- AppKit's, so the origin is the bottom-left corner and `+y` is up. This is what a pixel honestly is in a 3-D viewer: a point on the screen names a LINE through the world, and which point of that line was meant is the program's question. `scene:on-click` answers the common case over it. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:ray *v* 450.0 320.0)
(#(305.2 -412.7 690.0) #(-0.24 0.32 -0.55))
```
