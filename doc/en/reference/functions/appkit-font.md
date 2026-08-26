# appkit:font

`(appkit:font size &key bold)`

Answers the system `NSFont` at that point size, bold when `:bold` is true. `appkit:label` builds its own font from its `:size` and `:bold`, so this is for the views the widget layer does not wrap -- anything with a `setFont:`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *heading* (appkit:font 19 :bold t))
CL-USER> (objc:send *text-view* "setFont:" *heading*)
```
