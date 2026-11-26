# scene:fit

`(scene:fit v)`

Points the camera at the contents and backs it off far enough to frame them: `geom:bounds` over every solid, its centre as the target and its extent as the distance. A no-op on an empty viewer. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:fit *v*)
NIL
```
