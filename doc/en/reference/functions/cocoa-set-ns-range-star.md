# cocoa:set-ns-range*

`(cocoa:set-ns-range* range location length)`

Sets an `NSRange` -- a cons, or a foreign object of the type -- to `(location . length)` and answers it. Part of the macOS-only `cocoa` package, beside `objc`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke-into 'string *s* "substringWithRange:" (cocoa:set-ns-range* (cons 0 0) 6 5))
"world"
```
