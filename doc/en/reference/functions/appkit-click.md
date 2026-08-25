# appkit:click

`(appkit:click button)`

Performs the button's action as a user's click would -- the way a script drives a window without a human. Answers `nil`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS, interpreter only (`java -jar` or the `rontolisp` binary), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:click *button*)
NIL
> (appkit:text *label*)
"clicked 1 time(s)"
```
