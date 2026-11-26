# objc:objc-object-from-pointer

`(objc:objc-object-from-pointer pointer)`

The Lisp object associated with an Objective-C object: the `objc:standard-objc-object` of an instance of a class `objc:define-objc-class` defined (made by `make-instance`, or when Objective-C allocated it), the Lisp class for such a class, and `nil` for any other object. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:objc-object-from-pointer *s*)
NIL
```
