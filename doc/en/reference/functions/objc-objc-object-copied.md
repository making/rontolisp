# objc:objc-object-copied

`(objc:objc-object-copied old-object new-object)`

A generic function called when an `objc:standard-objc-object` is copied through `copyWithZone:` (`copy`), with the copy's own Lisp object. The built-in primary method copies the slots; an `:after` method is the counterpart of implementing `copyWithZone:`. Not called by the program. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defmethod objc-object-copied :after ((old my-object) (new my-object))
          (format t "copied~%"))
OBJC-OBJECT-COPIED
MY-APP> (slot-value (objc-object-from-pointer (invoke (make-instance 'my-object :slot1 :a) "copy")) 'slot1)
copied
:A
```
