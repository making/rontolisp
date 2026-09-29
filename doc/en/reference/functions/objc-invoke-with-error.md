# objc:invoke-with-error

`(objc:invoke-with-error class-or-object-pointer method &rest args)`

Sends `method` -- a string or the list form `objc:invoke` takes, whose name ends in `error:` or `Error:` (`startAndReturnError:`) -- with `args` followed by an `NSError **` it supplies itself. When the result says the call failed (`nil`, `NO` or zero) and the method wrote an error, signals `objc:ns-error`, which holds that `NSError`; otherwise answers what `objc:invoke` answers. A method whose name ends in neither is refused. Not in LispWorks, which has no `NSError` helper. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (handler-case (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                         "attributesOfItemAtPath:error:" "/no/such/file")
          (ns-error (e) (list (ns-error-domain e) (ns-error-code e))))
("NSCocoaErrorDomain" 260)
MY-APP> (null (invoke-with-error (invoke "NSFileManager" "defaultManager")
                                 "attributesOfItemAtPath:error:" "/"))
NIL
```
