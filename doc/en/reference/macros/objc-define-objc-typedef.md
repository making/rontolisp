# objc:define-objc-typedef

`(objc:define-objc-typedef (name [(:c-type type)]) [type])`

Defines `name` as another name for an FLI type, usable wherever the defining macros and `objc:invoke`'s list form take one. `:c-type`, when given, is the type and `type` is ignored. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`. The definition needs no runtime: the Objective-C side is made when the runtime is opened (`objc:ensure-objc-initialized`, or the first send). See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-typedef (count-type) (:unsigned :int))
COUNT-TYPE
MY-APP> (define-objc-method ("count:" count-type) ((self my-object) (items objc-object-pointer (array string)))
          (length items))
"count:"
MY-APP> (invoke (alloc-init-object "MyObject") "count:" #("a" "b" "c"))
3
```
