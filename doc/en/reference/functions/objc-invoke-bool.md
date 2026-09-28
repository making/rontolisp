# objc:invoke-bool

`(objc:invoke-bool class-or-object-pointer method &rest args)`

`objc:invoke` for a method that answers a `BOOL`: `nil` for `NO`, `t` otherwise. Plain `objc:invoke` answers `0` or `1` for the same method, as LispWorks does. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke-bool *s* "hasPrefix:" "hello")
T
CL-USER> (objc:invoke *s* "hasPrefix:" "hello")
1
```
