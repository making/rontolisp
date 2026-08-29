# metal:uniform

`(metal:uniform encoder index values &key stage)`

Sets `values` as the stage's bytes at buffer `index` -- a per-frame uniform small enough that Metal wants it inline rather than in a buffer. `:stage` is `:vertex` (the default) or `:fragment`; the two stages number their buffers independently, so index 0 of one is not index 0 of the other. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:uniform encoder 1 (linalg:transpose view-projection))
CL-USER> (metal:uniform encoder 0 eye :stage :fragment)
```
