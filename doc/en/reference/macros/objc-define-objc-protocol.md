# objc:define-objc-protocol

`(objc:define-objc-protocol name &key incorporated-protocols instance-methods class-methods)`

Declares the protocol `name`, a string, with the methods it lists as `(name result-type arg-type*)`. It declares, it does not create: as the manual says, a protocol is not defined in Lisp, and `(:objc-protocols "Name")` of `objc:define-objc-class` adopts one the runtime already has (warning when it has none). Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-protocol "NSCopying"
          :instance-methods (("copyWithZone:" objc-object-pointer (:pointer :void))))
"NSCopying"
MY-APP> (define-objc-class copyable () () (:objc-class-name "Copyable") (:objc-protocols "NSCopying"))
COPYABLE
```
