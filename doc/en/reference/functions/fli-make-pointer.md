# fli:make-pointer

`(fli:make-pointer &key address type pointer-type)`

A foreign pointer to `address` of the FLI type `type` (or the pointee of `pointer-type`), `:void` by default. Nothing is allocated. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:make-pointer :address 4096 :type :int)
#<Pointer to type :INT = #x0000000000001000>
```
