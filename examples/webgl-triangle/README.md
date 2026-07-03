# triangle.lisp — the WebGL hello world, driven from Lisp

The smallest complete `rontolisp:wasm-import` program: a Lisp program compiled
to WebAssembly that draws one colored triangle in the browser. If you want to
call JavaScript (or WebGL) from Lisp, start here; when you outgrow it,
[`webgl-cube/`](../webgl-cube) adds 3D (a vertex buffer, a depth test and
matrix math in Lisp), and [`webgl-galaxy/`](../webgl-galaxy) grows the same
idea into a full pipeline (uniforms, shader-error reporting, an animation
loop).

**Live demo:** <https://making.github.io/rontolisp/webgl-triangle/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File            | Purpose                                                      |
| --------------- | ------------------------------------------------------------ |
| `triangle.lisp` | The whole program: GLSL shaders, pipeline setup, one draw.    |
| `index.html`    | The host page: ten one-line WebGL2 bindings.                  |
| `triangle.wasm` | The compiled `--no-wasi` reactor (checked in, ~2 KB).         |
| `build.sh`      | Recompiles `triangle.lisp` to `triangle.wasm`.                |

## How it works

`triangle.lisp` declares ten host functions — the minimum slice of WebGL2
needed to compile two shaders, link them and draw:

```lisp
(rontolisp:wasm-import 'gl-create-shader :from "gl" :as "createShader"
                       :params '(:int) :returns :int)
(rontolisp:wasm-import 'gl-shader-source :from "gl" :as "shaderSource"
                       :params '(:int :string) :returns :void)
;; ... compileShader, createProgram, attachShader, linkProgram, useProgram,
;;     clearColor, clear, drawArrays
```

The GLSL sources are Lisp string constants; a `:string` parameter reaches the
host as a `(ptr, len)` pair into the module's exported linear memory. GL
objects (the shaders, the program) cross the boundary as `:int` handles into a
small table the page keeps.

The JavaScript side is the import object and nothing else:

```js
const handles = [];
const H = (obj) => handles.push(obj) - 1;
const str = (ptr, len) =>
  new TextDecoder().decode(new Uint8Array(lisp.memory.buffer, ptr, len));

const imports = {
  gl: {
    createShader: (type) => H(gl.createShader(type)),
    shaderSource: (sh, p, n) => gl.shaderSource(handles[sh], str(p, n)),
    // ... eight more one-liners
  },
};
const { instance: { exports: lisp } } = await WebAssembly.instantiate(bytes, imports);
lisp._initialize(); // runs the whole Lisp program: the triangle appears
```

There are no `rontolisp:wasm-export` directives and no frame loop: the whole
program is top-level forms, which a `--no-wasi` reactor runs inside
`_initialize()`. By the time that call returns, the triangle is on the canvas.

Two tricks keep it minimal:

- **No vertex buffer.** The vertex shader looks its corner positions and
  colors up by `gl_VertexID` (WebGL2 allows attributeless draws), so no
  buffer/attribute imports are needed at all.
- **No resize/viewport handling.** The canvas has a fixed backing store
  (`width="640" height="480"`), and a WebGL context's default viewport is the
  canvas size at creation.

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing triangle.lisp:
examples/webgl-triangle/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-triangle"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the ten
  functions above — the import object is the whole embedding API. With
  `--optimize` the shipped `triangle.wasm` is about 2 KB.
- For brevity there is no shader-compile error check; see
  [`webgl-galaxy/`](../webgl-galaxy) for the error-reporting pattern
  (`getShaderParameter` / `getShaderInfoLog` as a `:string` result).
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere).
