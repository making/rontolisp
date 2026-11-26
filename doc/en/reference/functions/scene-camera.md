# scene:camera

`(scene:camera v &key azimuth elevation distance target)`

Sets any of the four camera parameters and leaves the rest alone. The camera orbits `target` at `distance`, `azimuth` around z and `elevation` above the ground plane (clamped to +/-1.5 radians by the mouse, not here). Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:camera *v* :azimuth 0.85 :elevation 0.42 :distance 1250)
NIL
```
