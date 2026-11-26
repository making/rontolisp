# objc:current-super

`(objc:current-super)`

Inside the body of `objc:define-objc-method` or `objc:define-objc-class-method`, a value that `objc:invoke`, `objc:invoke-bool`, `objc:invoke-into` and `objc:can-invoke-p` send to the superclass of the class the method was defined on (`super` in Objective-C). Outside such a body it is an unbound variable. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-class my-special-object (my-object)
          ()
          (:objc-class-name "MySpecialObject"))
MY-SPECIAL-OBJECT
MY-APP> (define-objc-method ("areaOfWidth:height:" (:unsigned :int))
            ((self my-special-object)
             (width (:unsigned :int))
             (height (:unsigned :int)))
          (* 4 (invoke (current-super) "areaOfWidth:height:" width height)))
"areaOfWidth:height:"
MY-APP> (invoke (alloc-init-object "MySpecialObject") "areaOfWidth:height:" 6 7)
168
```
