# appkit:set-color

`(appkit:set-color view color)`

Sets a panel's fill colour, or any other control's text colour, and answers the colour. The counterpart of `appkit:set-text`: one verb that asks the view what it is. Part of the `appkit` package, a Cocoa widget layer written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (appkit:set-color *tile* (appkit:color 214 69 65))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #xBFA0400B6A239467>
CL-USER> (appkit:set-color *label* (appkit:color 25 60 210))
#<Pointer: OBJC:OBJC-OBJECT-POINTER = #xBFC7FCC2EA239467>
```
