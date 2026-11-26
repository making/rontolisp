# fli:null-pointer-p

`(fli:null-pointer-p pointer)`

True when the foreign pointer holds address 0. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:null-pointer-p (fli:make-pointer :address 0))
T
```
