# fli:size-of

`(fli:size-of type-name)`

The size in bytes of an FLI type, by the C layout rule: `:int` is 4, `:pointer` 8, `(:c-array :int 4)` 16, and the Foundation structures are LispWorks' own, `cocoa:ns-rect` 32 and the other three 16. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (mapcar #'fli:size-of '(:char :int :double cocoa:ns-rect))
(1 4 8 32)
```
