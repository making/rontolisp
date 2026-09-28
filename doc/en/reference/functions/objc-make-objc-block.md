# objc:make-objc-block

`(objc:make-objc-block type function)`

Makes an Objective-C block that calls `function`, answering an `objc:objc-block` that passes wherever a method or a C function takes a block. `type` is `(result-type (argument-type*))` in the types `objc:invoke`'s list form takes, or a name `objc:define-objc-block-type` defined; the block's arguments reach `function` converted by those types, as a method's reach its body, and its value is converted back. An error inside `function` is printed and the block answers zero. A block runs on the thread that calls it -- a libdispatch worker for a completion handler -- except in a `--native` executable, where a `void` block called on another thread waits for the main thread's event loop (the program's `sleep`) and a block answering a value there answers zero. Free it with `objc:free-objc-block`, or make it with `objc:with-objc-block`; a callee that keeps the block holds a copy, which keeps `function` alive after the free. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (defvar *add* (make-objc-block '(:int (:int :int)) (lambda (a b) (+ a b))))
*ADD*
MY-APP> *add*
#<OBJC:OBJC-BLOCK i@?ii live>
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
```
