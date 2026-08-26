# appkit:text

`(appkit:text view)`

A button's title, or any other control's string value, as a Lisp string. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (appkit:text *label*)
"hello"
```
