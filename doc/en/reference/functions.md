# Functions

Function reference is split one page per package. **Each function name in a
package's table links to its own page**, which has a fuller description and a
runnable example you can evaluate in your browser. Cross-cutting topics have
their own homes: the `make-array`/`aref` and hash-table operators are
described under [Arrays](data-types.md#arrays) and
[Hash tables](data-types.md#hash-tables) on the Data Types page, and each
function's deviations from Common Lisp are noted on its own page.

## Packages

| Package | Functions |
|---------|-----------|
| [`cl`](functions/cl.md) | The standard Common Lisp functions, used unqualified by `cl-user` |
| [`rontolisp`](functions/rontolisp.md) | Implementation-specific functions: `version`, async HTTP fetch/serve, JSON, TCP/TLS sockets, the WASM/JVM export and WIT hooks |
| [`linalg`](functions/linalg.md) | numpy-style vector/matrix operations |
| [`torch`](functions/torch.md) | A PyTorch-style tensor with automatic differentiation and an `nn`-style module layer |
| [`java`](functions/java.md) | Java interop by reflection (JVM interpreter only) |
| [`ffi`](functions/ffi.md) | C library interop |
| [`objc`](functions/objc.md) | The Objective-C runtime and AppKit through the foreign function API (macOS interpreter only) |
| [`appkit`](functions/appkit.md) | A Cocoa widget layer over `objc` |
| [`geom`](functions/geom.md) | Solid modeling over the `linalg` kernels |
| [`metal`](functions/metal.md) | A Metal drawing surface on an `appkit` window over `objc` |
| [`scene`](functions/scene.md) | A 3-D viewer for `geom` solids over `metal` |
| [`asdf`](functions/asdf.md) | A limited, API-compatible subset of ASDF system definitions |
| [`uiop`](functions/uiop.md) | ASDF's portability layer -- see also [The uiop Package](uiop.md) for the sub-package layout |
| [`ql` / `ql-dist`](functions/ql-and-ql-dist.md) | A limited, API-compatible subset of Quicklisp |
| [`usocket`](functions/usocket.md) | A [usocket](https://github.com/usocket/usocket)-compatible shim over the `rontolisp:tcp-*` built-ins |

See [Packages](packages.md) for the full package system (`:use`, qualifiers,
`defpackage`).
