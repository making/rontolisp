# metal Package Functions

The `metal` package is a Metal drawing surface on an `appkit` window -- the
layer, the device, the command queue, the render pass, the drawable, present and
commit, plus the shader, pipeline and buffer helpers every Metal program writes
identically. Written in rontolisp over the `objc` verbs and loaded on first use,
so it is macOS only, on the interpreter and a compiled `.class` / `.jar`; never
a `.wasm`. It is **not part of Common Lisp**; reference its names with the
`metal:` qualifier. `metal:context` is also a CLOS class name. It stands on its
own -- `geom` and `scene` are not needed to use it -- and the four
`examples/macos/metal-*.lisp` programs drive it directly. Each name below links
to its own page; the [macOS GUI guide](../../guides/objc-appkit.md) covers the
surface as a whole.

| Function | Example | Result |
|----------|---------|--------|
| `metal:attach` | `(metal:attach win :depth t)` | a `metal:context`: a `CAMetalLayer` on the window's content view, its device and its command queue |
| `metal:offscreen` | `(metal:offscreen :width 256 :height 192)` | a `metal:context` with no window: the same pipelines and the same `metal:frame`, drawing into a texture |
| `metal:pixels` | `(metal:pixels ctx)` | the last offscreen frame: `width * height * 4` bytes, BGRA, row 0 at the top |
| `metal:device` | `(metal:device ctx)` | the `MTLDevice` -- the GPU, and the receiver of every `new...` selector |
| `metal:layer` | `(metal:layer ctx)` | the `CAMetalLayer` frames are presented to |
| `metal:queue` | `(metal:queue ctx)` | the `MTLCommandQueue` command buffers are committed to |
| `metal:library` | `(metal:library ctx source)` | an `MTLLibrary`: Metal Shading Language compiled at run time, signalling with the compiler's own diagnostics |
| `metal:pipeline` | `(metal:pipeline ctx lib "v" "f")` | an `MTLRenderPipelineState` over two named shader functions; `:blend t` makes it additive |
| `metal:depth-state` | `(metal:depth-state ctx :writes nil)` | an `MTLDepthStencilState`: how a pipeline uses the depth attachment |
| `metal:floats` | `(metal:floats '(1 2 3))` | a packed single-float array -- a Metal buffer's exact bytes |
| `metal:buffer` | `(metal:buffer ctx (geom:mesh s))` | an `MTLBuffer` holding those numbers, copied once and never changed |
| `metal:shared-buffer` | `(metal:shared-buffer ctx 4096)` | an `MTLBuffer` in shared storage, whose contents the CPU rewrites |
| `metal:upload` | `(metal:upload buf values)` | copies the numbers into a shared buffer |
| `metal:uniform` | `(metal:uniform enc 1 m)` | sets a small per-frame uniform inline; `:stage` is `:vertex` (default) or `:fragment` |
| `metal:frame` | `(metal:frame ctx fn)` | draws one frame, calling `fn` with the render command encoder; skips the frame when no drawable is free |
| `metal:run` | `(metal:run ctx fn :fps 30)` | the timer that calls `metal:frame`, an `NSTimer` on the main thread |
| `metal:resize` | `(metal:resize ctx 1024 640)` | follows the layer, the drawable size and the depth texture to a new content size |
| `metal:set-clear-color` | `(metal:set-clear-color ctx '(0 0 0 1))` | the colour a frame starts from |
| `metal:+triangle+` ... | `metal:+line+` | the enum members a drawing program spells out: the primitive, the cull mode, the winding and the depth comparison |

