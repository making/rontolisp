# objc:trace-invoke

`(objc:trace-invoke method)`

Traces every `objc:invoke` of `method`: the call and its value are printed to `*trace-output*`. `objc:untrace-invoke` removes the trace. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:trace-invoke "length")
"length"
CL-USER> (objc:invoke *s* "length")
(objc:invoke #<Pointer: OBJC:OBJC-OBJECT-POINTER = #xB9D0A0E3E4E9A3A1> "length")
  => 11
11
```
