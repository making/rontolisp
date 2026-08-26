# `objc:` cannot move bytes, so Metal is out of reach

Difficulty: Medium

## What is missing

The `objc:` binding marshals objects, numbers, strings and structs, and nothing else. It
has no way to hand a BLOCK OF BYTES to a `^v` / `^@` parameter, no way to read one back,
and no way to see what a `NSError **` out-parameter was filled with. That is a hole in the
binding itself, not a Metal problem: `NSData`, `getBytes:length:`, `[MTLBuffer contents]`,
`initWithBytes:length:` and every `...error:` selector in Cocoa are on the far side of it.

Found 2026-08-26 while answering "can `objc:`/`appkit:` carry a WebGL-class demo the way
`examples/browser/webgl-triangle` does". The answer is yes, and the spike proved it on
`java -jar` with NO Java change at all:

- **OpenGL is not reachable and never will be.** `glClear` / `glDrawArrays` are plain C
  functions; `objc_msgSend` does not reach them and `objc:` binds no C entry points.
  Deprecated on macOS since 10.14 besides.
- **Metal is almost entirely Objective-C**, so `objc:send` reaches all of it.
- The one C entry point Metal seems to need, `MTLCreateSystemDefaultDevice()`, is
  avoidable: `[[CAMetalLayer layer] preferredDevice]` is a PROPERTY and answers the same
  device. This is the fact that makes the whole thing work.
- Shaders compile at run time from a Lisp string:
  `newLibraryWithSource:options:error:`.
- The frame loop is `appkit:timer`; the drawing surface is a `CAMetalLayer` set on
  `appkit:window`'s `contentView`.

Only the bytes had to be smuggled: the spike hand-rolled base64 in Lisp over
`%ieee754-single-bits` and went in through
`[[NSData alloc] initWithBase64EncodedString:options:]`. ~25 lines of Lisp to say
"here are some floats", and a failed shader compile is an unexplained `nil`, because the
`NSError **` cannot be read.

## The surface to add

Three things, all in the existing `objc:` seam.

1. **`(objc:data buffer)` -> an `NSMutableData`.** The bytes are exactly what
   `write-sequence` would write for the same buffer (`.kb/binary-sequence-io.md`):
   little-endian, row-major, a packed float array of any rank (f32 = 4 bytes, f64 = 8) or
   a packed `(unsigned-byte 8|16|32)` vector. A string goes as its UTF-8 bytes.
   `NSMutableData`, not `NSData`, so `mutableBytes` gives writable scratch and one verb
   serves both directions.
2. **`(objc:bytes object)` -> a packed `(unsigned-byte 8)` vector** of an `NSData`'s
   bytes. The read direction, symmetric with `objc:data`.
3. **`:error` as an `objc:send` argument** for a `^@` parameter: the binding allocates the
   out slot, passes it, and -- when the call fills the slot AND answers nil / NO / 0, the
   Cocoa convention -- SIGNALS with the `NSError`'s `localizedDescription`, domain and
   code. Never a silent nil, which is what `.kb/objc.md` already promises for everything
   else.

`PackedBuffer` is today a private record inside `Environment`; it becomes a
package-private class in `eval` so `objc:data` / `objc:bytes` and
`%read-sequence-packed` / `%write-sequence-packed` share ONE definition of what a packed
buffer's bytes are. That is the point: the two operators cannot drift.

## What it costs

- `LispNames` + the `PackageRegistry` `OBJC` symbol set.
- `eval/ObjcBridge` (the verbs) and `am.ik.objc/ObjcRuntime` (the `NSData` calls, the out
  slot in `marshal`/`send`).
- `codegen/jvm/JvmObjcTemplate`, the hand-kept twin -- the compiled value model is a
  `float[]` / `double[]` for a packed float array and `long[]{width, e0, ...}` for a
  packed integer vector, so both are reachable there without the `_iv*` helpers.
- `doc/{en,ja}/reference/functions/objc-data.md`, `objc-bytes.md`, `_catalog.yaml`, and
  the guide table in `doc/{en,ja}/guides/objc-appkit.md`.
- `ObjcInteropTest`, `JvmObjcInteropCompilerTest`, and rows in
  `ObjcNativeImageForeignConfigTest` for every selector the new examples send.
- `reachability-metadata.json`: the Metal / CAMetalLayer shapes, or the native binary
  refuses the examples with `MissingForeignRegistrationError`.
- `.kb/objc.md`.

## The examples

`examples/macos/metal-triangle.lisp` and `examples/macos/metal-cube.lisp`, the AppKit
twins of `examples/browser/webgl-triangle` and `webgl-cube`. Both open a window, so
neither goes in `examples.yaml`; verified by hand on `java -jar`, the native binary and
`-o Prog.class`, like every other GUI example.

The cube needs no depth buffer: a cube is convex, so back-face culling alone is correct
(`setCullMode:` + `setFrontFacingWinding:`). Face normals come from `dfdx`/`dfdy` in the
fragment shader, so the vertex format stays position + colour.

The cube's matrices come from the built-in `linalg` package rather than hand-written
arithmetic: a linalg result IS a packed float array, so `linalg:matmul` ->
`objc:data` -> `setVertexBytes:` needs no conversion at all, and one `linalg:transpose`
bridges row-major storage to Metal's column-major `float4x4`.

## Status

Done 2026-08-26, uncommitted. Verified: the whole suite (8996), `docs-tool`, the native
image build + `CiSpecE2eTest`, `-Pweb compile`; both examples run interpreted, compiled to
a `.class` and on the native binary. The rendering itself was checked WITHOUT a display --
render into an offscreen `MTLTexture`, `getBytes:...` into an `objc:data` block, pixels
back through `objc:bytes` -- and the interpreter, the JVM class and the native binary
produce the same picture. This file is what still has to be deleted (plus its
`.todo/.history.md` row) when the work is committed.
