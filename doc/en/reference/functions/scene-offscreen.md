# scene:offscreen

`(scene:offscreen &key width height background)`

A viewer with no window: the same pipelines, the same camera and the same render function as [`scene:viewer`](scene-viewer.md), drawing into a texture [`scene:snapshot`](scene-snapshot.md) can read back. `:width` and `:height` are pixels. It has no input -- there is nothing to click -- so the camera moves through `scene:camera` and `scene:fit`, and a frame is `scene:snapshot`. This is what makes the renderer testable: no test may open a window, so without it the camera, the projection, the model matrices, the winding convention and the depth test would ship with nothing checking them. Part of the `scene` package: macOS only, never a `.wasm`. See the [solid modeling guide](../../guides/solid-modeling.md).

```console
CL-USER> (defvar *v* (scene:offscreen :width 320 :height 240))
CL-USER> (scene:add *v* (geom:box 200 :color (geom:vec3 1.0 0.2 0.2)))
CL-USER> (scene:fit *v*)
CL-USER> (length (scene:snapshot *v*))
307200
```
