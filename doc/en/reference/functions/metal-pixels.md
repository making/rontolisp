# metal:pixels

`(metal:pixels ctx)`

The last frame an offscreen context drew, as a packed `(unsigned-byte 8)` vector of `width * height * 4` bytes in the texture's own order: **BGRA**, row 0 at the top, tightly packed. Not converted to RGBA -- the format is the layer's format, and a reader that has to know one order can as well know the real one. Signals when the context draws into a window rather than a texture, since there is nothing there to read back. Part of the `metal` package: macOS only, never a `.wasm`. See [`metal:offscreen`](metal-offscreen.md).

```console
CL-USER> (defvar *ctx* (metal:offscreen :width 4 :height 4 :clear '(0.0 0.0 1.0 1.0)))
CL-USER> (metal:frame *ctx* (lambda (encoder) encoder))
CL-USER> (subseq (metal:pixels *ctx*) 0 4)
#(255 0 0 255)
```
