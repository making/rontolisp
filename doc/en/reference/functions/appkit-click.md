# appkit:click

`(appkit:click button)`

Performs the button's action as a user's click would -- the way a script drives a window without a human. Answers `nil`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (appkit:click *button*)
NIL
CL-USER> (appkit:text *label*)
"clicked 1 time(s)"
```
