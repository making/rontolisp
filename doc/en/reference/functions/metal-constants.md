# metal:+triangle+ metal:+line+ metal:+point+ metal:+triangle-strip+ metal:+cull-none+ metal:+cull-front+ metal:+cull-back+ metal:+winding-clockwise+ metal:+winding-counter-clockwise+ metal:+compare-less+ metal:+compare-always+

`metal:+triangle+ metal:+line+ metal:+point+ metal:+triangle-strip+
metal:+cull-none+ metal:+cull-front+ metal:+cull-back+
metal:+winding-clockwise+ metal:+winding-counter-clockwise+
metal:+compare-less+ metal:+compare-always+`

Metal's enums are plain integers on the wire, and these are the members a drawing program spells out: the primitive it draws (`drawPrimitives:...`), the cull mode and winding it sets on the encoder, and the depth comparison it hands `metal:depth-state`. The pixel formats, load and store actions, blend factors and storage modes are `metal:attach` / `metal:pipeline` / `metal:frame`'s own business and are not exported. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (list metal:+point+ metal:+line+ metal:+triangle+ metal:+triangle-strip+)
(0 1 3 4)
CL-USER> (objc:send encoder "setCullMode:" metal:+cull-back+)
```
