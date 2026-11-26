# scene:axes

`(scene:axes v mode)`

Which axis triads to draw: `nil` (the default) none, `:world` the world frame alone, `:bodies` each solid's OWN frame, or `:both`. These are the viewer's furniture -- line triads with no thickness, the world one scaled by the view distance so it stays legible at any zoom. An origin indicator that is an OBJECT, placed where the caller says and with a shaft thickness and a pointed tip, is [`geom:triad`](geom-triad.md): three [`geom:arrow`](geom-arrow.md) solids added like any other, which is why nothing is drawn here unless it was asked for. A body triad is what makes a kinematic chain readable -- it is drawn at the solid's world transform and sized from its model-space extent. There is no text: `geom:label-of` names a frame and the triad locates it. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (scene:axes *v* :both)
NIL
```
