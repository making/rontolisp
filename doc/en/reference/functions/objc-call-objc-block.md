# objc:call-objc-block

`(objc:call-objc-block type block &rest args)`

Calls a block -- one `objc:make-objc-block` made, a block object a method answered, or an address -- on the calling thread, with `args` converted by `type` (the designator `objc:make-objc-block` takes) and the result converted back. A block does not reliably carry its own signature, so the caller states it. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (call-objc-block '(:int (:int :int)) *add* 3 4)
7
```
