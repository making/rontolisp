# objc:objc-block-live-p

`(objc:objc-block-live-p block)`

`t` while `block` has not been freed with `objc:free-objc-block`, else `nil`. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (objc-block-live-p *add*)
T
MY-APP> (free-objc-block *add*)
NIL
MY-APP> (objc-block-live-p *add*)
NIL
```
