# objc:retain

`(objc:retain pointer)`

Sends `retain` and answers `pointer`. The reference is the program's to release: a pointer value's collector does not release an explicit retain, which is how a program keeps an object alive after dropping its pointer. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *o* (objc:alloc-init-object "NSObject"))
*O*
CL-USER> (objc:retain-count (objc:retain *o*))
2
```
