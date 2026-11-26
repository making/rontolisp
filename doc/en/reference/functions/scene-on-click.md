# scene:on-click

`(scene:on-click v hook)`

Calls `hook` with one argument -- the world point where the click ray meets the plane through the orbit target facing the camera -- every time the view is clicked; `nil` removes it. A click is a press released without travelling more than a few points, so clicking and orbiting are one gesture and neither needs a modifier. The plane is the one plane a viewer can pick without being told, and it is what makes "click where you see" true from any camera angle; `scene:ray` is the line itself, for a program that wants a different plane. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:on-click *v* (lambda (p) (geom:place *marker* :translation p)))
NIL
```
