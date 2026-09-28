# objc:untrace-invoke

`(objc:untrace-invoke method)`

Removes the trace `objc:trace-invoke` set on `method`. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:untrace-invoke "length")
"length"
```
