# objc:class

`(objc:class "ClassName")`

Answers the Objective-C class of that name, or signals when no loaded framework declares it. A class is a receiver like any other: send it a class method, or `alloc` it. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:class "NSString")
#<objc NSString>
CL-USER> (objc:send (objc:class "NSString") "stringWithUTF8String:" "hi")
#<objc NSTaggedPointerString>
```

`objc:send` also accepts a class *name* as the receiver, so `(objc:send "NSString" ...)` needs no `objc:class`.
