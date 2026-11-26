# objc:alloc-init-object

`(objc:alloc-init-object class)`

`(objc:invoke (objc:invoke class "alloc") "init")`: a new instance of `class`, a class pointer or its name. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:alloc-init-object "NSMutableArray")
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C08A20>
```
