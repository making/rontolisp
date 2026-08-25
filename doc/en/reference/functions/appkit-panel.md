# appkit:panel

`(appkit:panel window &key (x 20) (y 20) (width 100) (height 100) fill (radius 0) (border 0) border-color)`

Adds a filled rectangle -- an `NSBox` in its custom form, optionally rounded and bordered -- to the window's content view and answers it. Coordinates are AppKit's: the origin is the window's bottom-left corner. Its colour is changed with `appkit:set-color` and it answers a click through `appkit:on-click`, so a panel is the tile a board game is built from. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *tile*
    (appkit:panel *win* :x 20 :y 20 :width 34 :height 34
                  :fill (appkit:color 104 116 146) :radius 7))
> (appkit:set-color *tile* (appkit:color 230 233 241))
#<objc _NSTaggedPointerColor>
```
