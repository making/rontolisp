# appkit:close

`(appkit:close window)`

Closes (hides) the window; the Lisp value stays valid. Answers `nil`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS, interpreter only (`java -jar` or the `rontolisp` binary), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:close *win*)
NIL
> (appkit:visible-p *win*)
NIL
```
