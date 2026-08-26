# objc:on-main

`(objc:on-main function)`

Calls a zero-argument function on the process's main thread -- the one AppKit belongs to -- and answers its value; an error it signals is re-signalled to the caller. A function already on the main thread runs inline, so nesting cannot deadlock. Each `objc:send` hops by itself; this batches several into one hop. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:on-main (lambda () (+ 1 2)))
3
CL-USER> (objc:on-main
    (lambda ()
      (let ((win (objc:send (objc:send "NSWindow" "alloc")
                            "initWithContentRect:styleMask:backing:defer:"
                            (list 0 0 400 200) 15 2 nil)))
        (objc:send win "setReleasedWhenClosed:" nil)
        (objc:send win "makeKeyAndOrderFront:" nil)
        win)))
#<objc NSWindow>
```
