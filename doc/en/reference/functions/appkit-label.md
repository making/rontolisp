# appkit:label

`(appkit:label window text &key (x 20) (y 20) (width 200) (height 24))`

Adds an `NSTextField` label with that text and frame to the window's content view and answers it. Coordinates are AppKit's: the origin is the window's bottom-left corner. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
> (appkit:text *label*)
"no clicks yet"
```
