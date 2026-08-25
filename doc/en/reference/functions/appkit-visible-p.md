# appkit:visible-p

`(appkit:visible-p window)`

Whether the window is on screen: `t` after `appkit:window`, `nil` after it is closed. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:visible-p *win*)
T
```
