# metal:library

`(metal:library ctx source)`

Compiles Metal Shading Language `source` at run time and answers the `MTLLibrary`. A shader that does not compile signals an ordinary Lisp condition carrying the Metal compiler's own diagnostics, line and caret included. Part of the `metal` package, a Metal drawing surface written in rontolisp over `objc` and loaded on first use: macOS (`java -jar`, the `rontolisp` binary, or a compiled `.class` / `.jar`; never a `.wasm`), with a display. See the [macOS GUI guide](../../guides/objc-appkit.md).

```console
CL-USER> (defvar *lib* (metal:library *ctx* *shaders*))
CL-USER> (handler-case (metal:library *ctx* "nonsense") (error (e) :rejected))
:REJECTED
```
