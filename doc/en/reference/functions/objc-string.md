# objc:string

`(objc:string "text")`

An `NSString` holding the text. A Lisp string passed directly to an object parameter of `objc:send` is converted the same way, so this is for the cases where the string itself is the value you keep. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (objc:send (objc:string "hello") "length")
5
> (objc:send (objc:send (objc:string "hello") "uppercaseString") "UTF8String")
"HELLO"
```
