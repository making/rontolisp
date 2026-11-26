# objc:autorelease

`(objc:autorelease pointer)`

Gives up one reference the way `objc:release` does, to the innermost autorelease pool, which releases it when it is drained; with no pool, to the pointer's collector. Answers `pointer`. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:with-autorelease-pool ()
           (objc:ns-string-to-string (objc:autorelease (objc:string-to-ns-string "pooled"))))
"pooled"
```
