# objc:ensure-objc-initialized

`(objc:ensure-objc-initialized &key modules)`

Loads each of `modules` -- the path of a framework binary or a dylib -- and opens the Objective-C runtime, answering `nil`. The other functions open the runtime on their first use, so unlike LispWorks calling this first is optional; what it adds is loading frameworks AppKit does not already bring in, whose classes do not exist until one is loaded. A path that cannot be loaded signals an `error` naming it. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:ensure-objc-initialized
           :modules '("/System/Library/Frameworks/Vision.framework/Vision"))
NIL
CL-USER> (objc:coerce-to-objc-class "VNRecognizeTextRequest")
#<Pointer: OBJC:OBJC-CLASS = #x00000001FB3C2A10>
```
