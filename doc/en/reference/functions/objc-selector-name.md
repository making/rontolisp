# objc:selector-name

`(objc:selector-name selector)`

The name of a selector; a string is answered as it is, unregistered. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:selector-name (objc:coerce-to-selector "setWidth:height:"))
"setWidth:height:"
```
