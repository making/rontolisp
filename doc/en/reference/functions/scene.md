# scene Package Functions

The `scene` package is a 3-D viewer for `geom` solids, over `metal` and
`appkit`: an orbit/pan/dolly camera, a ground grid, world and body axis triads,
solid/wireframe shading and an animation hook. macOS only, for the same reason
`metal` is. It is **not part of Common Lisp**; reference its names with the
`scene:` qualifier, and `scene:viewer-state` is also a CLOS class name. A viewer
is an instance rather than a set of globals, so two windows can exist in one
image and orbit independently. **No triangle is touched by Lisp during a frame**
-- each solid's mesh goes to the GPU once and a frame hands it one 4x4 matrix
per solid. Each name below links to its own page; the [solid modeling
guide](../../guides/solid-modeling.md) covers the model half.

| Function | Example | Result |
|----------|---------|--------|
| `scene:viewer` | `(scene:viewer :title "arm")` | a window with a Metal surface on it, and the viewer that drives it |
| `scene:offscreen` | `(scene:offscreen :width 320 :height 240)` | a viewer with no window, over the same render function -- what makes the renderer testable |
| `scene:snapshot` | `(scene:snapshot v)` | one frame of an offscreen viewer as its pixels, BGRA |
| `scene:add` | `(scene:add v s1 (geom:triad))` | the last solid added; a list argument is spliced, and its mesh reaches the GPU when it is first drawn |
| `scene:drop` | `(scene:drop v s1 (geom:triad))` | the last solid removed from the viewer, its GPU buffers released |
| `scene:clear` | `(scene:clear v)` | `nil`; every solid removed, the grid and camera untouched |
| `scene:contents` | `(scene:contents v)` | the solids being drawn, in the order they were added |
| `scene:fit` | `(scene:fit v)` | `nil`; the camera points at the contents and backs off far enough to frame them |
| `scene:camera` | `(scene:camera v :azimuth 0.85)` | `nil`; sets any of azimuth / elevation / distance / target and leaves the rest |
| `scene:grid` | `(scene:grid v :extent 1200 :spacing 100)` | `nil`; rebuilds the ground grid, or drops it when `:extent` is `nil` |
| `scene:grid-color` | `(scene:grid-color v (geom:vec3 0.2 0.5 0.4))` | `nil`; the grid's colour |
| `scene:background` | `(scene:background v '(0 0 0 1))` | `nil`; the colour a frame starts from |
| `scene:shading` | `(scene:shading v :wireframe)` | `nil`; `:solid`, `:wireframe` or `:both` (the default) |
| `scene:axes` | `(scene:axes v :both)` | `nil`; `:world` (the default), `:bodies`, `:both` or `nil` |
| `scene:refresh` | `(scene:refresh v)` | `nil`; draws exactly one frame |
| `scene:animate` | `(scene:animate v hook)` | `nil`; draws at 60 fps, calling `hook` once before each frame |
| `scene:wait` | `(scene:wait v)` | `nil`, once the viewer's window has been closed |
| `scene:window-of` | `(scene:window-of v)` | the `NSWindow` -- the escape hatch to `appkit:` and raw `objc:send` |
| `scene:context-of` | `(scene:context-of v)` | the `metal:context` -- the escape hatch to the drawing surface |

