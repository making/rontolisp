# fli:free-foreign-object

`(fli:free-foreign-object pointer)`

Frees the memory [`fli:allocate-foreign-object`](fli-allocate-foreign-object.md) allocated and answers `nil`. A null pointer is ignored; the pointer must not be used afterwards. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:free-foreign-object (fli:allocate-foreign-object :type :int))
NIL
```
