# objc:objc-block-pointer

`(objc:objc-block-pointer block)`

The address of `block`'s literal, an integer, or `nil` once `objc:free-objc-block` freed it. Rarely needed: an `objc:objc-block` passes where a block goes as it stands. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
MY-APP> (integerp (objc-block-pointer *add*))
T
```
