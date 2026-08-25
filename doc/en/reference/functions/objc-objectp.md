# objc:objectp

`(objc:objectp value)`

Whether the value is an Objective-C object reference (an object or a class). Works on every machine. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (objc:objectp (objc:string "x"))
T
> (objc:objectp "x")
NIL
```
