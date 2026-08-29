# metal:pipeline

`(metal:pipeline ctx lib vertex-name fragment-name &key blend)`

An `MTLRenderPipelineState` over the two named functions of `lib`, drawing into the layer's pixel format. `:blend` makes it additive (one + one), which is what a glow pass wants. The depth attachment format follows the context rather than the caller, because a pipeline's attachments must match the pass it draws into. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *solid* (metal:pipeline *ctx* *lib* "solid_vertex" "solid_fragment"))
CL-USER> (defvar *glow* (metal:pipeline *ctx* *lib* "sprite_vertex" "sprite_fragment" :blend t))
```
