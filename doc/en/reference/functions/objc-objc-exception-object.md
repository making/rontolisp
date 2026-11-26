# objc:objc-exception-object

`(objc:objc-exception-object condition)`

The thrown object an `objc:objc-exception` carries, an `objc:objc-object-pointer` holding one reference (released when the pointer is collected), or `nil` when `nil` was thrown. A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (invoke-into 'string (objc-exception-object *e*) "name")
"NSRangeException"
```
