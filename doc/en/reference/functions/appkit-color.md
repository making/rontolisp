# appkit:color

`(appkit:color r g b &optional (alpha 1.0))`

Answers an `NSColor` with those red, green and blue components, each 0-255, and that alpha (0.0 clear, 1.0 opaque). It is what every colour argument in the package takes -- a window's `:background`, a panel's `:fill`, a label's `:color`, `appkit:set-color`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *amber* (appkit:color 255 176 74))
> (appkit:label *win* "007" :x 20 :y 20 :width 96 :height 44 :color *amber*)
#<objc RontoLispAppKitLabel>
```
