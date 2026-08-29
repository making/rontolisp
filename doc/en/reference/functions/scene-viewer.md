# scene:viewer

`(scene:viewer &key title width height background)`

Opens a window with a Metal surface on it and answers the viewer -- a CLOS instance, not a set of globals, so two windows can exist in one image and orbit independently. Drag to orbit, shift-drag to pan, scroll to dolly, and resize the window; the camera gestures redraw by themselves, while the mutators below do not (a loop adding sixty solids must not draw sixty frames -- the step after them is `scene:refresh`). Part of the `scene` package, a 3-D viewer for `geom` solids written in rontolisp over `metal` and `appkit` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (defvar *v* (scene:viewer :title "arm" :width 900 :height 640))
CL-USER> (scene:add *v* (geom:cylinder :radius 60 :height 140))
CL-USER> (scene:fit *v*)
CL-USER> (scene:refresh *v*)
```
