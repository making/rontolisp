# appkit:quit

`(appkit:quit)`

Ends the application, the way Cmd-Q does. It is the way out of a program whose whole interface is a menu bar — [`appkit:status-item`](appkit-status-item.md) with `:dock nil` — since no window's closing could release [`appkit:wait`](appkit-wait.md). The process does not come back, so nothing after the call runs. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (appkit:menu (list (list "Quit" #'appkit:quit "q")))
#<objc NSMenu>
```
