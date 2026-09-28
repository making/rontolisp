# objc:objc-object-destroyed

`(objc:objc-object-destroyed object)`

A generic function called when an `objc:standard-objc-object`'s reference count reaches zero, inside its `dealloc`. The built-in primary method does nothing; an `:after` method is the counterpart of implementing `dealloc`. Until then the Lisp object stays alive: `make-instance`'s reference is the program's to `objc:release`. Not called by the program. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defmethod objc-object-destroyed :after ((object my-object))
          (format t "destroyed ~a~%" (slot-value object 'slot1)))
OBJC-OBJECT-DESTROYED
MY-APP> (release (make-instance 'my-object :slot1 :gone))
destroyed GONE
NIL
```
