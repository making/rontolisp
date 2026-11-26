# metal:resize

`(metal:resize ctx width height)`

Follows the layer to a new content size in POINTS: its frame, its drawable size (points times the backing scale) and, when `metal:attach` was asked for one, a fresh depth texture -- a resized window is a different drawable and the old attachment no longer matches it. A caller that tracks a resizable window calls this and then draws a frame. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:resize *ctx* 1024 640)
NIL
```
