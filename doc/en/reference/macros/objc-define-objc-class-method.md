# objc:define-objc-class-method

`(objc:define-objc-class-method (name result-type [result-style]) ((object-var class-name [pointer-var]) (arg-var arg-type [arg-style])*) form*)`

Defines the class method `name` of a class `objc:define-objc-class` defined, as `objc:define-objc-method` defines an instance method. `object-var` is bound to the Lisp class, `pointer-var` to the Objective-C class, and `(objc:current-super)` sends to the superclass's class methods. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-class-method ("describeClass" objc-object-pointer) ((class my-object))
          (concatenate 'string "class " (invoke-into 'string (current-super) "description")))
"describeClass"
MY-APP> (invoke-into 'string "MyObject" "describeClass")
"class MyObject"
```
