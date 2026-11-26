# objc:release

`(objc:release pointer)`

Gives up one reference the pointer holds -- an explicit retain first, else the one the object arrived with -- and sends `release`. A pointer holding none signals instead of over-releasing, so manual-style code cannot release twice what the collector would release once. An autorelease pool from `objc:make-autorelease-pool` is drained instead. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:release *o*)
NIL
CL-USER> (objc:release *o*)
NIL
CL-USER> (objc:release *o*)
error: objc:release: #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600000C04010> holds no reference this program can give up
```
