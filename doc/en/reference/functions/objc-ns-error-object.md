# objc:ns-error-object

`(objc:ns-error-object condition)`

The `NSError` an `objc:ns-error` carries, an `objc:objc-object-pointer` holding one reference (released when the pointer is collected). A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (invoke-into 'string (ns-error-object *e*) "domain")
"NSCocoaErrorDomain"
```
