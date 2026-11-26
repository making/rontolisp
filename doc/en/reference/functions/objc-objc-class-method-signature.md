# objc:objc-class-method-signature

`(objc:objc-class-method-signature class-spec method-name)`

Three values describing a method of a class (a name, a class pointer, or an object whose class is meant): the argument types, receiver and selector included, the result type, and the runtime's type encoding. The instance method is preferred to a class method of the same name; `nil` when neither exists. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:objc-class-method-signature "NSString" "rangeOfString:")
(OBJC:OBJC-OBJECT-POINTER OBJC:SEL OBJC:OBJC-OBJECT-POINTER)
(:STRUCT COCOA:NS-RANGE)
"{_NSRange=QQ}24@0:8@16"
```
