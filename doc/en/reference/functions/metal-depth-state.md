# metal:depth-state

`(metal:depth-state ctx &key writes compare)`

An `MTLDepthStencilState`: how a pipeline uses the depth attachment. `:writes nil` is the glow pass -- it READS the depth the solid pass wrote, so a sprite behind the machine is hidden, but writes none of its own, so sprites do not occlude each other. `:compare` defaults to `metal:+compare-less+`. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *depth* (metal:depth-state *ctx*))
CL-USER> (defvar *read-only* (metal:depth-state *ctx* :writes nil))
```
