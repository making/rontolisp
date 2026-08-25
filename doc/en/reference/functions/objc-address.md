# objc:address

`(objc:address object)`

The object's address as an integer -- the identity a hash table can key on (two references to one object have one address). Signals for anything but an Objective-C object. Part of the macOS-only `objc` package -- the interpreter under `java -jar` and in the `rontolisp` native binary, never a compiled `.class` or `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (integerp (objc:address (objc:string "x")))
T
```
