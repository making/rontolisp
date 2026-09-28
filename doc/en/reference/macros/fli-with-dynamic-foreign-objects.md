# fli:with-dynamic-foreign-objects

`(fli:with-dynamic-foreign-objects ((var type &key nelems initial-element initial-contents fill)*) form*)`

Evaluates `form`s with each `var` bound to a foreign object of the FLI type `type` (unevaluated; the keys as [`fli:allocate-foreign-object`](../functions/fli-allocate-foreign-object.md) takes them), and frees every one on every exit, answering the last form's values. LispWorks allocates them on the stack; here they are heap memory, so a pointer kept past the form dangles all the same. Part of the macOS-only `fli` package, beside `objc` -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (fli:with-dynamic-foreign-objects ((result-value :int))
          (invoke (invoke "NSScanner" "scannerWithString:" "42 apples") "scanInt:" result-value)
          (fli:dereference result-value))
42
```
