# objc:bytes

`(objc:bytes data)`

The contents of an `NSData` as a packed `(unsigned-byte 8)` vector. The read direction of [`objc:data`](objc-data.md): give a selector an `objc:data` block to write into, then read back what it wrote.

Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary) and a compiled `.class` / `.jar`, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:bytes (objc:data "hi"))
#(104 105)
CL-USER> (length (objc:bytes (objc:send (objc:string "hello") "dataUsingEncoding:" 4)))
5
```
