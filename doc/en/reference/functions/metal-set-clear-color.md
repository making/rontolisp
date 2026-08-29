# metal:set-clear-color

`(metal:set-clear-color ctx rgba)`

The colour a frame starts from, as an `(r g b a)` list. `metal:attach` takes the first one; this changes it afterwards. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:set-clear-color *ctx* '(0.0 0.0 0.0 1.0))
NIL
```
