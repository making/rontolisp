# appkit:label

`(appkit:label window text &key (x 20) (y 20) (width 200) (height 24) (size 13) color (align :left) bold)`

Adds an `NSTextField` label with that text to the window's content view and answers it. Coordinates are AppKit's: the origin is the window's bottom-left corner. The string is centred VERTICALLY in the rectangle given -- a plain `NSTextField` draws at the top of its own frame, so a label handed a tall rectangle would otherwise hang from the ceiling -- which is what puts a digit in the middle of a tile. `:align` is `:left`, `:center` or `:right`, `:size` and `:bold` pick the font and `:color` the text colour. The label answers a click through `appkit:on-click`. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *label* (appkit:label *win* "no clicks yet" :x 20 :y 120 :width 380))
CL-USER> (appkit:text *label*)
"no clicks yet"
CL-USER> (appkit:label *win* "3" :x 20 :y 20 :width 34 :height 34 :size 19 :align :center :bold t)
#<objc RontoLispAppKitLabel>
```
