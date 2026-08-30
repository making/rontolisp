# ffi Package Functions

The `ffi` package binds plain C through the JVM's foreign function API — no
JNI, no bundled native library, no reflection, which is why it works in the
**native binary** as well as under `java -jar` and in a compiled `.class` /
`.jar`. Neither WASM backend has a foreign function API, so a program reaching
it refuses to compile there. It is **not part of Common Lisp**; reference its
functions with the `ffi:` qualifier. These are primitives, and they exist for
one consumer: a binding should be written against `cffi:defcfun`, not against
these verbs — see the [C libraries guide](../../guides/cffi.md).

| Function | Example | Result |
|----------|---------|--------|
| `ffi:open` | `(ffi:open "libsqlite3.so.0")` | a library handle, an integer (no argument = the process) |
| `ffi:symbol` | `(ffi:symbol (ffi:open) "strlen")` | the symbol's address, a pointer (`nil` when absent) |
| `ffi:call` | `(ffi:call addr :long '(:string) "hello")` | the C function's answer, marshalled by the return type |
| `ffi:callback` | `(ffi:callback fn :int '(:int :int))` | a C function pointer that calls the Lisp function |
| `ffi:alloc` | `(ffi:alloc 8)` | a pointer to that many bytes (`malloc`) |
| `ffi:free` | `(ffi:free p)` | `nil`, the block released |
| `ffi:peek` | `(ffi:peek p :int 8)` | the value of that type at the address plus offset |
| `ffi:poke` | `(ffi:poke p :double 1.5)` | the value, written at the address |
| `ffi:size` | `(ffi:size :pointer)` | the type's size in bytes |
| `ffi:align` | `(ffi:align :double)` | the type's alignment in bytes |
| `ffi:pointerp` | `(ffi:pointerp 42)` | `t` for a foreign pointer |
| `ffi:address` | `(ffi:address 4096)` | the pointer for an integer, the integer for a pointer |
| `ffi:errno` | `(ffi:errno)` | the `errno` the calling thread's last call left |

