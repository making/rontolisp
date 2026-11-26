# objc:ns-error-domain

`(objc:ns-error-domain condition)`

The domain of the `NSError` an `objc:ns-error` carries, a string (`"NSCocoaErrorDomain"`). `objc:ns-error` is signalled by `objc:invoke-with-error`. A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (ns-error-domain *e*)
"NSCocoaErrorDomain"
```
