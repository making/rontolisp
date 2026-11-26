# fli:dereference

`(fli:dereference pointer &key index type copy-foreign-object)`

Answers the object `pointer` points at -- element `index` (default 0) of an array of them -- read as the pointer's type, or as `type` when given. Numbers, `t` / `nil` for a boolean type, an `objc:objc-object-pointer` for an object and a foreign pointer for a pointer; `setf` writes one. A structure or an array is not a Lisp value here: `copy-foreign-object` `nil` answers a foreign pointer to it, `t` a copy in freshly allocated memory, and the default `:error` signals. A null pointer or a pointer to `:void` without `type` signals. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (fli:with-dynamic-foreign-objects ((n :int :initial-element 41))
           (setf (fli:dereference n) (+ 1 (fli:dereference n)))
           (fli:dereference n))
42
```
