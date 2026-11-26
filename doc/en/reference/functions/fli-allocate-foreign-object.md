# fli:allocate-foreign-object

`(fli:allocate-foreign-object &key type pointer-type nelems initial-element initial-contents fill)`

Allocates `nelems` (default 1) objects of the FLI type `type` -- or the pointee of `pointer-type`, `(:pointer type)` -- in zeroed foreign memory and answers a foreign pointer to the first. `fill` sets every byte; `initial-element` sets every object and `initial-contents`, a sequence, the first ones. The memory is the process's heap on every target and lives until [`fli:free-foreign-object`](fli-free-foreign-object.md). Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *p* (fli:allocate-foreign-object :type :double :nelems 3 :initial-contents '(1 2.5 3)))
*P*
CL-USER> (fli:dereference *p* :index 1)
2.5
CL-USER> (fli:free-foreign-object *p*)
NIL
```
