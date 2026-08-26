# appkit:set-text

`(appkit:set-text view text)`

Sets a button's title, or any other control's string value, and answers the text. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (appkit:set-text *label* "hello")
"hello"
CL-USER> (appkit:set-text *button* "Again")
"Again"
```
