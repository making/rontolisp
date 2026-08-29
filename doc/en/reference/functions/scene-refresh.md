# scene:refresh

`(scene:refresh v)`

Draws exactly one frame. This is the step after a batch of mutators, and all a static scene ever needs -- the camera gestures redraw by themselves. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:refresh *v*)
NIL
```
