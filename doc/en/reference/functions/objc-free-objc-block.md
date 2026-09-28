# objc:free-objc-block

`(objc:free-objc-block block)`

Frees the storage of a block `objc:make-objc-block` made and gives up the program's hold on its function; answers `nil`, and freeing a freed block does nothing. A callee that kept the block holds a copy of its own, so the function stays alive until the last copy is released; the freed `objc:objc-block` itself signals when it is passed or called. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (free-objc-block *add*)
NIL
MY-APP> *add*
#<OBJC:OBJC-BLOCK i@?ii freed>
```
