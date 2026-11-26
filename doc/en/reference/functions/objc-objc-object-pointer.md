# objc:objc-object-pointer

`(objc:objc-object-pointer object)`

The pointer of an object: a pointer answers itself, an `objc:standard-objc-object` its Objective-C object's pointer, and a class `objc:define-objc-class` defined (`(find-class 'my-object)`) its Objective-C class. `objc:objc-object-pointer` is also the type of every Objective-C object value. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (eq (objc:objc-object-pointer *s*) *s*)
T
CL-USER> (typep *s* 'objc:objc-object-pointer)
T
```
