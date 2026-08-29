# scene:window-of

`(scene:window-of v)`

The `NSWindow` the viewer draws into -- the escape hatch to `appkit:` and to raw `objc:send` for anything the viewer does not offer. Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (appkit:visible-p (scene:window-of *v*))
T
```
