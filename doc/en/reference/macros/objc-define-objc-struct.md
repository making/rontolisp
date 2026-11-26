# objc:define-objc-struct

`(objc:define-objc-struct (name (:foreign-name "Name") [(:typedef-name alias)]) (slot-name slot-type)*)`

Defines the structure type `name`, usable as `(:struct name)` (and `alias`) in `objc:invoke`'s list form and in the defining macros. `:foreign-name` is the name the runtime's encodings use. A value of it is a vector of its fields in memory order, as `objc:invoke` passes and answers any structure other than the four `cocoa` ones. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-struct (pair (:foreign-name "_Pair"))
          (:first :float)
          (:second :float))
PAIR
MY-APP> (define-objc-method ("pair" (:struct pair)) ((this my-object))
          (vector 1.0 2.0))
"pair"
MY-APP> (invoke (alloc-init-object "MyObject") "pair")
#(1.0 2.0)
```
