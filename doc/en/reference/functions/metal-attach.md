# metal:attach

`(metal:attach window &key clear scale depth)`

Replaces the window's content view backing with a `CAMetalLayer` and answers the `metal:context` every other function here takes. `:clear` is the `(r g b a)` a frame starts from, `:scale` the backing-store factor (2 for a Retina display) and `:depth` asks for a depth attachment -- which anything but a convex shape needs, and which every pipeline built afterwards then declares. The device comes from the layer's `preferredDevice`, so no C entry point is involved. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *win* (appkit:window "metal" :width 640 :height 400 :dark t))
CL-USER> (defvar *ctx* (metal:attach *win* :clear '(0.05 0.06 0.09 1.0) :depth t))
CL-USER> (objc:send (objc:send (metal:device *ctx*) "name") "UTF8String")
"Apple M4 Max"
```
