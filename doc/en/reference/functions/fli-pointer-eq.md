# fli:pointer-eq

`(fli:pointer-eq pointer1 pointer2)`

True when two foreign pointers hold the same address, whatever their types. Two pointers to one address need not be `eq`. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:pointer-eq (fli:make-pointer :address 4096 :type :int) (fli:make-pointer :address 4096))
T
```
