# objc:bytes

`(objc:bytes data)`

The contents of an `NSData` (or any object answering `length` and `bytes` as one does) as a fresh `(unsigned-byte 8)` vector. The read direction of [`objc:data`](objc-data.md): give a selector an `objc:data` block to write into, then read back what it wrote. Anything but an object pointer -- a class included -- signals an `error`.

Not in LispWorks' interface. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:bytes (objc:data "hi"))
#(104 105)
CL-USER> (objc:bytes (objc:invoke (objc:string-to-ns-string "hello") "dataUsingEncoding:" 4))
#(104 101 108 108 111)
```
