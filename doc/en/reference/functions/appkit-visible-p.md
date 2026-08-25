# appkit:visible-p

`(appkit:visible-p window)`

Whether the window is on screen: `t` after `appkit:window`, `nil` after it is closed. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS, interpreter only (`java -jar` or the `rontolisp` binary), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:visible-p *win*)
T
```
