# objc:can-invoke-p

`(objc:can-invoke-p class-or-object-pointer method)`

Whether the receiver has `method`: `t` or `nil`. A string names a class and asks about its class methods. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:can-invoke-p *s* "length")
T
CL-USER> (objc:can-invoke-p *s* "frame")
NIL
```
