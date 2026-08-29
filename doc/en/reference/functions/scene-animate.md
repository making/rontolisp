# scene:animate

`(scene:animate v &optional hook)`

Draws at 60 fps, calling `hook` once before each frame. The hook is where a pose changes -- a joint angle, an IK step, a target that moves -- and it costs the renderer nothing, because a solid that moves needs no re-upload. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:animate *v* (lambda () (geom:turn *joint* 0.02 :z)))
NIL
```
