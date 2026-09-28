# objc:string-to-ns-string

`(objc:string-to-ns-string string &optional autoreleasep)`

An `NSString` holding `string`. The program owns it: `objc:release` gives it up; with `autoreleasep` it goes to the current autorelease pool instead. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke (objc:string-to-ns-string "hi") "length")
2
```
