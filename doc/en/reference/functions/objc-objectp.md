# objc:objectp

`(objc:objectp value)`

Whether the value is an Objective-C object reference (an object or a class). Works on every machine. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

The type of such a reference is `objc:object` wherever the package runs: `type-of` answers it, and `typep`, `typecase` and a `defmethod` specializer accept it. It is no `structure-object`.

```console
CL-USER> (objc:objectp (objc:string "x"))
T
CL-USER> (objc:objectp "x")
NIL
CL-USER> (type-of (objc:string "x"))
OBJC:OBJECT
CL-USER> (typep (objc:string "x") 'objc:object)
T
```
