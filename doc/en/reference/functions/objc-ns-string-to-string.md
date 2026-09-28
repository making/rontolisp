# objc:ns-string-to-string

`(objc:ns-string-to-string ns-string &optional preserve-line-terminators)`

The characters of an `NSString` as a Lisp string. Unless `preserve-line-terminators`, a CR LF pair and a lone CR each become a newline. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:ns-string-to-string *s*)
"hello world"
```
