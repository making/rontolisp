# objc:objc-exception-name

`(objc:objc-exception-name condition)`

The name of the exception an `objc:objc-exception` carries, a string (`"NSRangeException"`); for a thrown object that is not an `NSException`, its class name, and `"nil"` when `nil` was thrown. `objc:objc-exception` is signalled by the innermost `objc:invoke`, C function or block call running when an Objective-C exception is raised inside it. A reader of a condition of the macOS-only `objc` package; see the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *e* (handler-case (invoke (invoke "NSArray" "array") "objectAtIndex:" 5)
                      (objc-exception (e) e)))
*E*
MY-APP> (objc-exception-name *e*)
"NSRangeException"
```
