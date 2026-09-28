# objc:ns-error-code

`(objc:ns-error-code condition)`

The code of the `NSError` an `objc:ns-error` carries, an integer. A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (ns-error-code *e*)
260
```
