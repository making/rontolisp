# objc:with-autorelease-pool

`(objc:with-autorelease-pool (option*) form*)`

Evaluates `form`s with a fresh autorelease pool and drains it on every exit, non-local ones included, answering the last form's values. `option`s must be empty. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defun object-description (object)
           (objc:with-autorelease-pool ()
             (objc:invoke-into 'string object "description")))
OBJECT-DESCRIPTION
CL-USER> (object-description (objc:invoke "NSNumber" "numberWithInt:" 42))
"42"
```
