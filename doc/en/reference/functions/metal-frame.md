# metal:frame

`(metal:frame ctx fn)`

Draws one frame: takes the next drawable, clears it, calls `fn` with the render command encoder so the program can set its pipeline and draw, then presents and commits. `fn` runs on the main thread. When the layer has no drawable free the frame is skipped, which is what a dropped frame is. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:frame *ctx*
    (lambda (encoder)
      (objc:send encoder "setRenderPipelineState:" *solid*)
      (objc:send encoder "drawPrimitives:vertexStart:vertexCount:" metal:+triangle+ 0 3)))
```
