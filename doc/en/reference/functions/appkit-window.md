# appkit:window

`(appkit:window title &key (width 480) (height 300))`

Creates an `NSWindow` of that content size, titled, centered, shown and made key, and activates the application. The value is the window; closing it (the red button or `appkit:close`) hides it and does not end the process. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS, interpreter only (`java -jar` or the `rontolisp` binary), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *win* (appkit:window "hello" :width 420 :height 200))
> (appkit:visible-p *win*)
T
```
