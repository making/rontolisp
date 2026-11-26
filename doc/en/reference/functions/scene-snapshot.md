# scene:snapshot

`(scene:snapshot v)`

One frame of an offscreen viewer, as its pixels: `width * height * 4` bytes, BGRA, row 0 at the top (see [`metal:pixels`](metal-pixels.md)). The frame is drawn through `scene:refresh` like every other frame, so what comes back is what a window would show. Signals on a viewer that has a window, which has no texture to read. Part of the `scene` package: macOS only, never a `.wasm`. See [`scene:offscreen`](scene-offscreen.md).

```console
CL-USER> (defvar *v* (scene:offscreen :width 64 :height 64))
CL-USER> (scene:grid *v* :extent nil)
CL-USER> (defvar *px* (scene:snapshot *v*))
CL-USER> (list (aref *px* 0) (aref *px* 1) (aref *px* 2))
(23 17 14)
```
