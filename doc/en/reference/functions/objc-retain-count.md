# objc:retain-count

`(objc:retain-count pointer)`

The object's `retainCount`. A tagged pointer (a short `NSString`, a small `NSNumber`) answers a meaningless maximum. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:retain-count (objc:invoke "NSObject" "new"))
1
```
