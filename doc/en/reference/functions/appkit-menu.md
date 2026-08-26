# appkit:menu

`(appkit:menu items)`

Builds an `NSMenu` whose items are Lisp closures. An entry is `(title handler)`, optionally with a key equivalent third — `(list "Quit" #'appkit:quit "q")` — and the keyword `:separator` is a dividing line. The handler takes no arguments and runs on the main thread, inside AppKit's event loop, exactly as a button's `:on-click` does. Hang the menu off [`appkit:status-item`](appkit-status-item.md). Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:menu (list (list "Count" (lambda () (setq *n* (+ *n* 1))))
                     :separator
                     (list "Quit" #'appkit:quit "q")))
#<objc NSMenu>
```
