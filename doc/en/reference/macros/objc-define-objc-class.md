# objc:define-objc-class

`(objc:define-objc-class name (superclass*) (slot-spec*) class-option*)`

Defines `name` as a CLOS class that implements an Objective-C class. Beside the `defclass` options it takes `(:objc-class-name "Name")`, the Objective-C class to create; `(:objc-superclass-name "Name")`, its superclass when no Lisp superclass implements one (`NSObject` by default); `(:objc-instance-vars ("name" type)...)`, instance variables read with `objc:objc-object-var-value`; and `(:objc-protocols "Name"...)`, protocols the class adopts. With no `superclass`, the class inherits `objc:standard-objc-object`. A class that names no Objective-C class and inherits none is a mixin: its methods are installed on every subclass that names one. A class of that name this process defined before (a re-evaluated definition) is reused with its methods replaced; one Lisp did not define is refused. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

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
