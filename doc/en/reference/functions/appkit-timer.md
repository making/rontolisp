# appkit:timer

`(appkit:timer seconds fn)`

Starts a repeating `NSTimer` that calls `fn` with no arguments every `seconds` until it answers `nil`, which invalidates the timer; answers the timer, which `(objc:send timer "invalidate")` also stops. `fn` runs on the main thread, inside AppKit's event loop, so it may repaint the window directly. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *ticks* 0)
CL-USER> (appkit:timer 1
    (lambda ()
      (setq *ticks* (+ *ticks* 1))
      (appkit:set-text *label* (format nil "~a s" *ticks*))
      (< *ticks* 10)))
#<objc __NSCFTimer>
```
