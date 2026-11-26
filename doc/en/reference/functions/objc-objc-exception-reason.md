# objc:objc-exception-reason

`(objc:objc-exception-reason condition)`

The `reason` of the exception an `objc:objc-exception` carries, a string, or `nil` when it has none (a thrown object that is not an `NSException` has none). A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (objc-exception-reason *e*)
"*** -[__NSArray0 objectAtIndex:]: index 5 beyond bounds for empty array"
```
