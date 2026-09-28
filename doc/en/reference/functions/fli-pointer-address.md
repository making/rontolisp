# fli:pointer-address

`(fli:pointer-address pointer)`

The address a foreign pointer holds, as an integer -- what an `ffi:` function takes for a pointer. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:pointer-address (fli:make-pointer :address 4096))
4096
```
