# metal:run

`(metal:run ctx fn &key fps)`

Draws `fn` on a timer: one frame immediately, then `fps` a second (60 by default). The clock is `appkit:timer`, an `NSTimer` on the main thread, which is where Metal wants the frame anyway. Answers the timer, so `(objc:send timer "invalidate")` stops the loop. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (metal:run *ctx* (lambda (encoder) (draw encoder)) :fps 30)
#<objc __NSCFTimer>
```
