# objc:ns-error-description

`(objc:ns-error-description condition)`

The `localizedDescription` of the `NSError` an `objc:ns-error` carries, a string in the user's language. A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                                     "attributesOfItemAtPath:error:" "/no/such/file")
                      (ns-error (e) e)))
*E*
MY-APP> (ns-error-description *e*)
"The file “file” couldn’t be opened because there is no such file."
```
