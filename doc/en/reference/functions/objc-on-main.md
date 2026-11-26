# objc:on-main

`(objc:on-main function)`

Calls a zero-argument function on the process's main thread -- the one AppKit belongs to -- and answers its value; an error it signals is signalled again to the caller. A function already on the main thread runs inline, so nesting cannot deadlock. Each `objc:invoke` hops by itself; this batches several into one hop. In a `--native` executable the program already runs on the main thread, so it is a plain call. Not in LispWorks' interface. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:on-main (lambda () (+ 1 2)))
3
CL-USER> (objc:on-main
    (lambda ()
      (let ((win (objc:invoke (objc:invoke "NSWindow" "alloc")
                              "initWithContentRect:styleMask:backing:defer:"
                              #(0 0 400 200) 15 2 nil)))
        (objc:invoke win "setReleasedWhenClosed:" nil)
        (objc:invoke win "makeKeyAndOrderFront:" nil)
        win)))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #x0000000100CCC5C0>
```
