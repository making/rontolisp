# objc:objc-object-var-value

`(objc:objc-object-var-value object var-name &key result-pointer)`

The value of the instance variable `var-name`, declared with `(:objc-instance-vars ...)` of `objc:define-objc-class`, converted by its type; `setf` writes it (an object variable takes an object pointer, which the variable does not retain). A structure value is filled into `result-pointer` when it is given. An object without the variable signals. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (define-objc-class counter () () (:objc-class-name "Counter")
          (:objc-instance-vars ("count" :int)))
COUNTER
MY-APP> (defvar *c* (make-instance 'counter))
*C*
MY-APP> (setf (objc-object-var-value *c* "count") 17)
17
MY-APP> (objc-object-var-value *c* "count")
17
```
