# objc:data

`(objc:data buffer)`

An `NSMutableData` holding the buffer's bytes. `buffer` is a packed float array of any rank (single-float, 4 bytes an element; double-float, 8), a packed `(unsigned-byte 8|16|32)` vector, or a string (its UTF-8 bytes); anything else signals an `error`. The bytes are exactly what [`write-sequence`](write-sequence.md) would write for the same buffer -- little-endian, row-major -- so a `#f` matrix reaches a GPU vertex buffer with no conversion step in between.

This is how a block of memory crosses into Objective-C: `[data bytes]` answers the address a `void *` parameter wants, `[data mutableBytes]` is writable scratch a callee can fill, and [`objc:bytes`](objc-bytes.md) reads the result back. A `--native` executable does not accept a `bfloat16` array or a quantized matrix. Not in LispWorks' interface. Part of the macOS-only `objc` package -- the interpreter (`java -jar`, or the `rontolisp` native binary), a compiled `.class` / `.jar` and a `--native` executable for macOS on Apple silicon, never a `.wasm`; on a machine without the runtime it signals an `error`. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (objc:invoke (objc:data "hello") "length")
5
CL-USER> (objc:bytes (objc:data (make-array 2 :element-type 'single-float :initial-contents '(1.0 2.0))))
#(0 0 128 63 0 0 0 64)
CL-USER> (objc:data 42)
Error: objc:data expects a packed float array, a packed (unsigned-byte 8|16|32) vector or a string, got 42
```
