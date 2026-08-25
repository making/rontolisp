# objc:send

`(objc:send receiver "selector:with:" arg1 arg2)`

Sends a message. The receiver is an object, a class, or a class name; `nil` answers `nil`. The arguments and the result are marshalled by the selector's own type encoding, read from the runtime: an object parameter takes an object, `nil` or a string (sent as an `NSString`); a selector parameter takes its name; `BOOL` takes `t`/`nil`; a struct such as `NSRect` takes a list of numbers and comes back as one. A selector the receiver does not respond to, a wrong argument count or an ill-typed argument is an `error`. Every send runs on the main thread. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (objc:send (objc:string "hello world") "length")
11
> (objc:send (objc:string "hello world") "rangeOfString:" "world")
(6 5)
> (objc:send "NSNumber" "numberWithDouble:" 2.5)
#<objc __NSCFNumber>
> (objc:send (objc:send "NSNumber" "numberWithDouble:" 2.5) "doubleValue")
2.5
```

The guide's marshalling table lists every declared type and its Lisp form.
