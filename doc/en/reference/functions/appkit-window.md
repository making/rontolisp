# appkit:window

`(appkit:window title &key (width 480) (height 300) background dark)`

Creates an `NSWindow` of that content size, titled, centered, shown and made key, and activates the application. The value is the window; closing it (the red button or `appkit:close`) hides it and does not end the process. `:background` is an `NSColor` for the window itself and `:dark` asks for the dark appearance, which the title bar follows too -- without it the traffic lights sit on a light strip above a dark window. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *win* (appkit:window "hello" :width 420 :height 200))
> (appkit:visible-p *win*)
T
> (appkit:window "night" :background (appkit:color 26 29 38) :dark t)
#<objc NSWindow>
```
