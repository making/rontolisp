# objc:define-objc-method

`(objc:define-objc-method (name result-type [result-style]) ((object-var class-name [pointer-var]) (arg-var arg-type [arg-style])*) form*)`

Defines the instance method `name` (the selector with its colons) of a class `objc:define-objc-class` defined; `form`s are its body. `object-var` is bound to the receiver's `objc:standard-objc-object` (or its pointer when it has none), `pointer-var` to its pointer. Arguments and the result are converted by their declared FLI types, of any shape: integers, `:float` / `:double`, `objc:objc-bool` / `:boolean` as `t` / `nil`, `objc:objc-object-pointer`, `objc:sel`, `objc:objc-class`, a pointer as a foreign pointer to its declared type, and a structure as the Lisp value `objc:invoke` uses for it (answered, a foreign pointer to one is copied). An object argument's `arg-style` `string` converts an `NSString` to a string, `array` an `NSArray` to a vector, `(array style)` its elements too; `:foreign` passes the raw value. A string or a vector answered as an object becomes an `NSString` / `NSArray`. A non-keyword `result-style` names a variable bound to a foreign object of the result type, which the body fills (`fli:foreign-slot-value`, `cocoa:set-ns-rect*`) and which is answered. Inside the body `(objc:current-super)` sends to the superclass. An error in the body is printed and the method answers its zero value. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-class my-object ()
          ((slot1 :initarg :slot1 :initform nil))
          (:objc-class-name "MyObject"))
MY-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* width height))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MyObject") "areaOfWidth:height:" 6 7)
42
```
