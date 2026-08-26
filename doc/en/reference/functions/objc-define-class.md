# objc:define-class

`(objc:define-class "Name" "Superclass" methods &optional protocols)`

Registers a class whose methods are Lisp functions. `methods` is a list of `("selector:" function)` pairs; each function receives the receiver first, then the method's own arguments. A method's type comes from the superclass when it declares the selector, from an adopted protocol (`protocols`, a list of names) otherwise, and defaults to a target/action shape: no result, one object argument per colon. Re-defining a class rebinds its methods. Answers the class. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *cls*
    (objc:define-class "MyTarget" "NSObject"
      (list (list "invoke:" (lambda (self sender) (print sender) nil)))))
CL-USER> (defvar *target* (objc:send (objc:send *cls* "alloc") "init"))
CL-USER> (objc:send button "setTarget:" *target*)
CL-USER> (objc:send button "setAction:" "invoke:")
```

The callback shapes are a closed set (no arguments; one or two object arguments; one object argument answering `BOOL`, an object or an integer); an error inside a callback is printed, not signalled.
