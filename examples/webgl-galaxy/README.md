# galaxy.lisp — a spiral galaxy computed in Lisp, drawn by WebGL

This example is the `rontolisp:wasm-import` showcase: a Lisp program that
*calls into the browser*. The physics of a spiral galaxy (the classic
density-wave toy model) runs in Lisp compiled to WebAssembly; the rendering is
WebGL2. The two sides meet through four imported host functions — including
`sin` and `cos`, which the WASM backend does not provide and Lisp simply
borrows from JavaScript's `Math`.

**Live demo:** <https://making.github.io/rontolisp/webgl-galaxy/> (this
directory is published as a subpath of the GitHub Pages site by
`.github/workflows/pages.yaml`).

## What's in here

| File          | Purpose                                                        |
| ------------- | -------------------------------------------------------------- |
| `galaxy.lisp` | The simulation: orbits, spiral-arm twist, per-star hue/size.    |
| `index.html`  | The host page: WebGL2 point renderer + the import object.      |
| `galaxy.wasm` | The compiled `--no-wasi` reactor (checked in, ~5 KB).           |
| `build.sh`    | Recompiles `galaxy.lisp` to `galaxy.wasm`.                      |

## How the boundary works

`galaxy.lisp` declares what it needs from the host:

```lisp
(rontolisp:wasm-import 'draw-particle :from "gl" :as "drawParticle"
                       :params '(:float :float :float :float) :returns :void)
(rontolisp:wasm-import 'aspect-ratio :from "gl" :as "aspectRatio"
                       :params '() :returns :float)
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

and exports its entry points:

```lisp
(rontolisp:wasm-export 'init  :params '(:int)   :returns :void)
(rontolisp:wasm-export 'frame :params '(:float) :returns :void)
```

`index.html` supplies the entire host side as a plain import object:

```js
const imports = {
  gl:   { drawParticle(x, y, hue, size) { /* append to a Float32Array */ },
          aspectRatio: () => canvas.width / canvas.height },
  math: { sin: Math.sin, cos: Math.cos },
};
const { instance } = await WebAssembly.instantiate(bytes, imports);
instance.exports._initialize();      // --no-wasi reactor init
instance.exports.init(16000);
```

Each `requestAnimationFrame` tick, the page calls `exports.frame(t)`. Lisp
computes every star's position on its slowly precessing ellipse and answers
with one `draw-particle` call per star; the page batches those into a single
additive-blended `gl.POINTS` draw. At 16,000 stars and 60 fps that is about a
million Lisp-to-JavaScript calls per second — the counter in the corner keeps
score.

There is no randomness: a `--no-wasi` reactor has no entropy source, so the
stars are scattered with low-discrepancy sequences (the golden angle for orbit
phase, `sqrt 2 - 1` for radius). One subtlety worth stealing: the radius
sequence must *not* be built on the golden ratio, because the golden angle is
`2*pi*(1-phi)` — the two sequences would be exactly correlated and every star
would land on a single curve.

## Building and running

```bash
# from the repo root, once:
./mvnw clean package

# recompile the .wasm after editing galaxy.lisp:
examples/webgl-galaxy/build.sh

# serve and open (any static file server works):
jwebserver -p 8000 --directory "$PWD/examples/webgl-galaxy"
open http://localhost:8000/
```

The page needs a browser with WebAssembly GC support (Chrome 119+,
Firefox 120+, Safari 18.2+, Edge 119+).

## Notes

- The module is compiled with `--no-wasi`, so its *only* imports are the four
  functions above — the import object is the whole embedding API.
- `--optimize` tree-shakes the runtime: the shipped `galaxy.wasm` is about
  5 KB.
- On the interpreter and JVM backends the `rontolisp:wasm-import` directives
  define stubs that signal an error when called, so this program is
  WASM-only by nature (there is no host to draw with elsewhere).
