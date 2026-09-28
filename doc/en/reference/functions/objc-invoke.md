# objc:invoke

`(objc:invoke class-or-object-pointer method &rest args)`

Sends a message and answers its value. The receiver is an object pointer, a class pointer, or a string naming a class (a class method); `nil` answers `nil`. `method` is the selector with its colons (`"setWidth:height:"`), or a list `(name arg-types &key result-type variadic-num-of-fixed)` that states the types itself -- the form a variadic method needs. Arguments and the result are converted by the method's declared types: a string or a vector passes as an `NSString` / `NSArray` released when the call returns, `#(x y width height)` as an `NSRect`, `(location . length)` as an `NSRange`, `t` / `nil` as a `BOOL`, a foreign object ([`fli`](fli.md)) as a pointer or, copied, a structure; a `BOOL` result is `1` or `0`, an `NSRect` a vector, an `NSRange` a cons, an object an `objc:objc-object-pointer` and a pointer a foreign pointer. A method the receiver does not have signals `No method ... for object ...` before anything is sent. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *s* (objc:invoke "NSString" "stringWithUTF8String:" "hello world"))
*S*
CL-USER> (objc:invoke *s* "length")
11
CL-USER> (objc:invoke *s* "rangeOfString:" "world")
(6 . 5)
CL-USER> (objc:invoke (objc:invoke "NSScrollView" "alloc") "initWithFrame:" #(0 0 100 100))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000600003B04000>
```
