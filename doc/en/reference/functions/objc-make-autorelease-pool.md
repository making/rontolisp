# objc:make-autorelease-pool

`(objc:make-autorelease-pool)`

Makes an autorelease pool, current until `objc:release` drains it (with every pool made after it). Pools are kept in Lisp: every send runs in a pool of its own on the main thread, so a real `NSAutoreleasePool` could not span two of them. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (let ((pool (objc:make-autorelease-pool)))
           (objc:autorelease (objc:alloc-init-object "NSObject"))
           (objc:release pool))
NIL
```
