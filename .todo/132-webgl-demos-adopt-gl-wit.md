# The WebGL demos adopt `local:webgl/gl.wit`

**Status:** open, unstarted. Unblocked by `.todo/127` (`rontolisp:wit-import`, DONE
2026-07-14), whose Definition of Done spiked exactly this and stopped short of the
migration. `.todo/130` §5 deferred the WebGL family here and forbade `gl.lisp` work
inside itself, so until now the migration belonged to **no todo at all**. This is its
home.

Scope: the **import** side only. The export side of `heat3d` / `robot-arm` /
`platformer` is blocked on the `:as`-vs-kebab question, which stays in `.todo/130` §4.

## What the spike established (measured 2026-07-14, `.kb/wit.md`)

- A hand-written `local:webgl/gl.wit` lowered through `rontolisp:wit-import` produces a
  Preview 1 module **byte-identical** to today's hand-written `rontolisp:wasm-import`
  block (`cmp`), and **byte-identical again under `--optimize`**, with an import the
  program never calls still shaken out. So the migration cannot regress an artifact, and
  declaring the full WebGL2 union stays free.
- `:field-style` defaults to `:camel`, so a WIT label `create-shader` becomes the import
  field `createShader` — which is what every demo's hand-written JS import object already
  provides. **No `index.html` changes.**
- 28 of gl.lisp's 31 imports are already an exact kebab -> camel match.

## The decision that unblocks it (user, 2026-07-14)

**The WIT-generated names win. gl.lisp's hand-chosen Lisp aliases are not preserved, and
`wit-import` does NOT grow a per-function alias option.**

An alias mechanism would restore exactly the two-places-drift `wit-import` exists to kill,
on the one field the component model says must be canonical. Four functions are affected —
gl.lisp today spells them with *different words* than the WebGL API (not merely a different
case), so a single WIT label cannot serve both sides:

| WIT label (= the new Lisp name) | import field (camel) | today's gl.lisp name |
|---|---|---|
| `get-shader-parameter` | `getShaderParameter` | `shader-compiled-p` |
| `get-shader-info-log` | `getShaderInfoLog` | `shader-info-log` |
| `get-program-parameter` | `getProgramParameter` | `program-linked-p` |
| `get-program-info-log` | `getProgramInfoLog` | `program-info-log` |

Rename them at every call site. If a demo genuinely wants an idiomatic predicate, that is
an ordinary one-line Lisp defun over the binding (and `--optimize` shakes it when unused) —
but do not add them "for symmetry"; the WIT name is the honest one.

## The work

1. **`examples/browser/webgl-common/gl.wit`** — a `local:webgl` package with one `gl`
   interface holding the 31 WebGL2 entry points gl.lisp declares today. Types are all
   inside the Preview 1 import boundary (`s32`/`f32`/`bool`/`string`), so nothing is
   blocked. GL objects (shaders, programs, buffers, VAOs, uniform locations) keep crossing
   as `s32` handles into the page's table — a WIT `resource` would be *more* honest but
   lowers to a handle anyway and would gain nothing here; do not reach for it.
2. **`examples/browser/webgl-common/gl.lisp`** — the 31 `wasm-import` directives collapse
   to one `(rontolisp:wit-import "gl.wit" :interface "local:webgl/gl" :package gl)`. The
   `defpackage gl` is then **synthesized by the directive**, so the hand-written one goes
   too — but the enum constants (`gl:+float+`, ...) and the helper defuns
   (`gl:make-shader` / `gl:build-program`) still need a package to live in. Decide: either
   keep a hand-written `(defpackage gl ...)` and drop `:package` from the directive (the
   directive then binds into the current package, the `(in-package gl)` idiom the file
   already uses), or let the directive make the package and add the constants with
   `defconstant` under `(in-package gl)`. The first is probably less surprising — check
   which one `PackageResolver` is happier with, since `defpackage` on an existing package
   is an error.
3. **Rename the four functions** at every call site in `webgl-cube`, `webgl-galaxy`,
   `webgl-heat3d`, `webgl-robot-arm`, `webgl-platformer` (grep; `gl:build-program` /
   `gl:make-shader` inside gl.lisp itself are the main users).
4. **The per-demo staging imports** (`setFloat`, `setVertex`, `setKey`, ...) — decide
   whether each demo also gets its own tiny `.wit`, or stays on `rontolisp:wasm-import`.
   Recommendation: leave them on `wasm-import`. They are page-specific by design, and
   `wasm-import` is meant to stay as the WIT-free escape hatch (`.todo/124`'s closing
   note). Do not migrate them just to be tidy.
5. **`webgl-triangle`** is deliberately self-contained (11 of its own imports, no gl.lisp).
   Leave it on `wasm-import` as the "hello world with no shared package" showcase, and say
   so in its README so nobody retrofits it later.

## The actual prize (do it in this todo, not a follow-up)

Today each demo's `index.html` hand-writes its import object against a Lisp-side
declaration with **nothing checking the two agree** — `.kb/wasm-import.md` even records a
scar from it (webgl-heat3d has to provide `disable`/`depthMask` it never calls, because
the funcall dispatcher keeps same-arity import wrappers reachable). With `gl.wit` checked
in, **generate the JS import object from it** and have each page import the generated
module instead. That is `.todo/124`'s "follow-on prize" stated concretely, and it is the
only part of this migration that buys something a `.wit`-less build cannot have — the rest
is a byte-identical rewrite.

Where the generator lives is the open question: a `docs-tool/`-style side project, a
`--scaffold-*` CLI mode next to `--scaffold-wit`, or a checked-in generated file with a
test asserting it is up to date. Prefer the last (no new build step; the test is the
contract).

## Definition of done

- `gl.wit` checked in; `gl.lisp` down to one `wit-import` directive; the four renames done
  across all five demos.
- Every demo's Preview 1 `.wasm` is **byte-identical** to its pre-migration build under the
  same flags (`cmp`; assert it, do not assume it — the spike says it holds).
- The pages still run unchanged in a browser (no `index.html` edit should be needed; if one
  is, the `:field-style` default is wrong and that is a bug, not a migration cost).
- The JS import object is generated from `gl.wit`, and something fails if it drifts.
- `.todo/124` and `.kb/wasm-import.md` say **31**, not 34, wherever they count gl.lisp's
  imports.
- `.kb/wasm-import.md` + `.kb/wit.md` updated; the webgl READMEs say the boundary is a WIT
  now.
- `./mvnw test`; the browser demos are absent from `examples/examples.yaml` by construction
  (they cannot run headless), so the byte-identity `cmp` IS the regression test — run it by
  hand and record the numbers.
