# appkit:wait

`(appkit:wait window)`

Blocks the calling thread until the window is closed, polling twenty times a second, and answers `nil`. A script calls it last, because its process ends when the last form returns; the REPL does not need it. Never call it from a button's handler, which runs on the thread that would close the window. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:wait *win*)   ; returns once the window is closed
NIL
```
