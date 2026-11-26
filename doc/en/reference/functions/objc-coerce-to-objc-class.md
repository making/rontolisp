# objc:coerce-to-objc-class

`(objc:coerce-to-objc-class class)`

The class pointer a class name designates; a class pointer is answered as it is. A name no loaded image defines signals an `error`. One object per class, so two answers for one class are `eq`. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:coerce-to-objc-class "NSString")
#<Pointer: OBJC:OBJC-CLASS = #x00000001FA1263D8>
CL-USER> (eq (objc:coerce-to-objc-class "NSObject") (objc:invoke "NSObject" "class"))
T
```
