# appkit:on-click

`(appkit:on-click view handler)`

Makes a panel or a label answer a click: `handler` is called with the button number -- 1 for a left click, 3 for a right one (or a Ctrl-click) -- on the main thread, from inside AppKit's event loop, so it may touch the GUI freely. Given a button it sets that button's action instead, so one verb wires any widget; a button's own `:on-click` closure takes no argument, since a button has no right click. Answers the view. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (appkit:on-click *tile*
    (lambda (button)
      (appkit:set-text *label* (if (= button 3) "flagged" "opened"))))
#<objc RontoLispAppKitPanel>
```
