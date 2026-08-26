# appkit:status-item

`(appkit:status-item title &key menu (dock t))`

Puts an `NSStatusItem` of variable width in the system menu bar, its `title` drawn by the button the status bar owns, and hangs `menu` — an [`appkit:menu`](appkit-menu.md) — off it. `:dock nil` asks for the accessory activation policy: no Dock icon and no app switcher entry, the shape a menu bar program has, in which case [`appkit:quit`](appkit-quit.md) is the way out. Keep the answer in a variable: outside the status bar it owns the item's only reference, and letting it be collected takes the item out of the menu bar. [`appkit:set-text`](appkit-set-text.md) and [`appkit:text`](appkit-text.md) accept it, so a timer can rewrite the title. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *item*
    (appkit:status-item "λ" :dock nil
                        :menu (appkit:menu (list (list "Quit" #'appkit:quit "q")))))
#<objc NSStatusItem>
> (appkit:set-text *item* "λ 42")
"λ 42"
```
