# fli:pointerp

`(fli:pointerp object)`

True when `object` is a foreign pointer: what [`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) and [`fli:make-pointer`](fli-make-pointer.md) answer, and every pointer `objc:invoke` or a callback receives. Not an `ffi:` pointer. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:pointerp (objc:invoke (objc:data "abc") "bytes"))
T
```
