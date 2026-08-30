# objc Package Functions

The `objc` package binds the Objective-C runtime and AppKit through the foreign
function API — no reflection, so unlike `java:` it works in the **native
binary** as well as under `java -jar`. It is **macOS, interpreter only** (a
compiled `.class` or `.wasm` refuses it) and **not part of Common Lisp**;
reference its functions with the `objc:` qualifier. Each name below links to its
own page; the [macOS GUI guide](../../guides/objc-appkit.md) covers marshalling,
threads, ownership and the native binary's shape table.

| Function | Example | Result |
|----------|---------|--------|
| `objc:class` | `(objc:class "NSWindow")` | a class (`#<objc NSWindow>`) |
| `objc:send` | `(objc:send (objc:string "hi") "length")` | the result, marshalled by the selector's declared type |
| `objc:define-class` | `(objc:define-class "Target" "NSObject" (list (list "invoke:" fn)))` | a class whose methods are Lisp functions |
| `objc:on-main` | `(objc:on-main (lambda () ...))` | the function's value, computed on the main thread |
| `objc:string` | `(objc:string "hi")` | an `NSString` |
| `objc:data` | `(objc:data buffer)` | an `NSMutableData` holding the buffer's bytes |
| `objc:bytes` | `(objc:bytes data)` | an `NSData`'s bytes as a packed `(unsigned-byte 8)` vector |
| `objc:address` | `(objc:address obj)` | the object's address, an integer |
| `objc:objectp` | `(objc:objectp x)` | `t` for an Objective-C object |

