# objc:description

`(objc:description pointer)`

The object's `description`, as a Lisp string. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:description (objc:invoke "NSNumber" "numberWithInt:" 42))
"42"
```
