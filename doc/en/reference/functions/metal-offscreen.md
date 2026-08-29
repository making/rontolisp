# metal:offscreen

`(metal:offscreen &key width height clear depth)`

A `metal:context` with no window at all: frames are drawn into a texture of its own and read back with `metal:pixels`. `:width` and `:height` are pixels (there is no backing scale without a screen to have one), `:clear` is the `(r g b a)` a frame starts from and `:depth` asks for a depth attachment. Everything else -- `metal:library`, `metal:pipeline`, `metal:depth-state`, `metal:buffer`, `metal:uniform`, `metal:frame` -- is the same function a layer-backed context takes, which is the point: what this renders is what the window renders, not a second path that resembles it. The device comes from a throwaway `CAMetalLayer`'s `preferredDevice` exactly as `metal:attach`'s does, and no display is needed for that property to answer. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *ctx* (metal:offscreen :width 256 :height 192 :depth t))
CL-USER> (length (metal:pixels *ctx*))
196608
```
