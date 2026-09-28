# objc:coerce-to-selector

`(objc:coerce-to-selector method)`

The selector a name designates, registering it when needed; a selector is answered as it is. One object per name. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke-bool *s* "respondsToSelector:" (objc:coerce-to-selector "length"))
T
```
