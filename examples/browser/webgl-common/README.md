# webgl-common — the shared `gl` package for the WebGL demos

Every `examples/webgl-*` demo used to repeat the same block of
`rontolisp:wasm-import` directives for the WebGL2 API, the same WebGL enum
constants and the same shader-compilation helpers. `gl.lisp` factors that
block into one user-defined package (`defpackage gl`); a demo splices it in
at compile time with

```lisp
(require :gl "../webgl-common/gl.lisp")
```

and then talks to WebGL through the package:

```lisp
(let ((program (gl:build-program +vertex-shader-source+ +fragment-shader-source+)))
  (gl:use-program program)
  (gl:enable gl:+depth-test+)
  ...
  (gl:draw-arrays gl:+triangles+ 0 36))
```

## What it exports

- **The WebGL2 API, one entry point at a time** — `gl:create-shader`,
  `gl:shader-source`, ... `gl:draw-arrays`: the union of every literal
  WebGL2 entry the demos use. GL objects cross the boundary as `:int`
  handles into a table the page keeps; strings (GLSL source, uniform names,
  info logs) cross as `:string`.
- **The WebGL enum constants** — `gl:+vertex-shader+`, `gl:+float+`,
  `gl:+color-buffer-bit+`, ...
- **Shader helpers** — `(gl:make-shader type source)` compiles one shader
  and `(gl:build-program vs fs)` links a whole program, both reporting
  compile/link errors through the page's `ui.fail` import (which shows the
  error box and throws).

Each demo keeps its own page-specific staging imports (`setVertex`,
`setFloat`, ...) next to its own code — those are not part of the WebGL2 API
and their shapes differ per demo.

## Why importing the union is free

The demos compile with `--optimize`, and the tree-shaker drops unused host
imports from the finished module. So although this file declares every entry
any demo needs, each compiled `.wasm` only imports what that demo actually
calls — a page's JavaScript import object never has to provide more than its
own demo reaches. (One nuance: a program that takes functions as values —
e.g. through the spliced `linalg` library — keeps the same-arity wrappers
reachable through the `funcall` dispatcher, so a couple of extra one-line
bindings can survive; `webgl-heat3d` provides `disable`/`depthMask` for this
reason.)

## A note on the names

The quoted name of a `rontolisp:wasm-import` directive resolves in the
current package like a defun name, so the directives in `gl.lisp` are
written with plain unqualified names (`'create-shader`): under
`(in-package gl)` each one canonicalizes to `gl:create-shader` (or
`gl::fail` for the one unexported helper), which is exactly what call sites
resolve to. The host-facing import field still defaults to the bare name —
a package qualifier never leaks into the page's import object.

`examples/browser/webgl-triangle` deliberately does not use this package: it is the
smallest complete `rontolisp:wasm-import` program, and staying a single
self-contained file is the point.
