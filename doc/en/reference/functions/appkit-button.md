# appkit:button

`(appkit:button window title &key (x 20) (y 20) (width 120) (height 32) on-click)`

Adds a push button with that title and frame to the window and answers it. `on-click` is a zero-argument function that runs on the main thread when the button is clicked (or `appkit:click`ed); it may call back into the GUI. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
> (defvar *n* 0)
> (defvar *button*
    (appkit:button *win* "Click me" :x 20 :y 40
      :on-click (lambda ()
                  (setq *n* (+ *n* 1))
                  (appkit:set-text *label* (format nil "clicked ~a time(s)" *n*)))))
```
