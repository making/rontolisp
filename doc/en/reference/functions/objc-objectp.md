# objc:objectp

`(objc:objectp value)`

Whether the value is an `objc:objc-object-pointer` -- an object or a class (`objc:objc-class` is a subtype). A selector, a string, and an `objc:standard-objc-object` (whose pointer `objc:objc-object-pointer` answers) are not. It touches no runtime, so it works on every machine. Not in LispWorks' interface. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:objectp (objc:string-to-ns-string "x"))
T
CL-USER> (objc:objectp (objc:coerce-to-objc-class "NSString"))
T
CL-USER> (objc:objectp (objc:coerce-to-selector "length"))
NIL
CL-USER> (objc:objectp "x")
NIL
```
