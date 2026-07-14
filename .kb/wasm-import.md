# `rontolisp:wasm-import` (host functions callable from Lisp) + export `:as` aliases

`(rontolisp:wasm-import 'name :from "module" :as "field" :params '(T...) :returns T)` is
the reverse of `wasm-export`: it declares a host function (JS import object key
`module`, property `field`; wasmtime `--preload module=...`) and makes it callable from
Lisp like a top-level defun. Type designators are shared with `WasmExportCompiler`
(`:int`/`:float`/`:bool`/`:string`/`:s-expr`, `:void` return). Generic parsing lives in
`am.ik.rontolisp.compiler.WasmImportDirective` (shared with the JVM backend); WASM-side
validation/codegen in `WasmImportCompiler`.

**How the fixed-index invariant survives adding imports**: the WASM spec puts all
imported functions before all defined ones, so a new import would shift every `FUNC_*`
constant. Instead, each import becomes a **synthetic defun** (registered in Pass 1 with
marker body; Pass 2a reserves a `userFunctionBodies` slot, filled after the lambda pass
when `_str_from_mem`'s index is known). The wrapper unboxes each arg (`castI31GetS`,
`castFloatGetF64`, string ptr/len via `WasmExportCompiler.emitStringResult`), then emits
`call (PLACEHOLDER_FUNC_BASE=1<<27) + ordinal` (written with writeUnsignedLeb128), then
boxes the result. Because it IS a defun, `#'name`/`funcall`/`mapcar`/dispatch/`eval`
work with no extra wiring. Host-ABI func types are appended after the export wrapper
types (`TYPE_PROMISE + 1 + numExports + j`).

The **`am.ik.wasm.WasmImportInjector` post-pass** (reuses `WasmTreeShaker`'s
package-private section/opcode scanners) then rewrites the finished module: prepends the
import entries at the FRONT of the import section (indices 0..K-1; creates the section
before the function section under `--no-wasi`), remaps every `call`/`ref.func`
immediate (`>= placeholderBase -> ordinal`, else `+K`), and shifts export/start-section
function indices. Runs before `WasmTreeShaker.shake` (`--optimize` composes; unused
imports are shaken like unused WASI imports). The pre-injection module is invalid (calls
to 2^27) — never validate/emit it directly.

Modes: `--component` and `--no-gc` throw a clear `UnsupportedOperationException`.
Interpreter (`Environment`) and JVM (`JvmLispCompiler` pass 1 synthesizes a
`(defun name (...) (error ...))` stub via `WasmImportDirective`; the directive itself is
an ACONST_NULL no-op in `JvmExprCompiler`) define error-signalling stubs so shared
sources load everywhere. An import with a `:string` result forces the
`__ronto_alloc`/`_str_from_mem` helper pair on (flag `memoryHelpers`, superset of the
old `exportUsesMemory`); an `:s-expr` result forces `usesRead`.

**Export aliases**: `wasm-export` gained `:as "alias"` (string or quoted symbol) —
`Decl.exportName()` defaults to the Lisp name; used by both the GC backend export
section and `NoGcWasmCompiler`.

**wasm-import/wasm-export inside a user `defpackage` (the shared `gl` package)**: unlike
ordinary quoted data (which passes through `PackageResolver` untouched), the quoted name
argument of both directives IS package-resolved — `PackageResolver.resolveWasmDirective`
resolves it like a defun name against the current package, so
`(rontolisp:wasm-import 'create-shader ...)` under `(in-package gl)` registers the
synthetic defun as `gl:create-shader` (or `gl::name` for an unexported symbol), matching
what call sites canonicalize to; an explicitly qualified name is a fixed point. Only the
name argument is special: the `:params` keyword list and a lenient quoted-symbol `:as`
alias stay untouched under the quote exemption. The host-facing default (`:as` omitted:
import field / export name) is the bare member name, never the qualified spelling
(`WasmImportDirective`/`WasmExportCompiler.unqualifiedMember`).
`examples/browser/webgl-common/gl.lisp` is the showcase: one `defpackage gl` holding the
WebGL2 API union + enum constants + `gl:make-shader`/`gl:build-program`, spliced into
each webgl demo by a compile-time `(require :gl "../webgl-common/gl.lisp")`; `--optimize`
shakes the entries a demo never calls, so declaring the union is free. Caveat: a program
that takes functions as values (e.g. via the spliced linalg library) keeps same-arity
import wrappers reachable through the funcall dispatcher — webgl-heat3d ends up
importing `disable`/`depthMask` it never calls, and its page provides those two
bindings for that reason.

**It is now also a LOWERING TARGET** (todo 127, `.kb/wit.md`): on the Preview 1 backend a
`(rontolisp:wit-import "gl.wit" :interface "local:webgl/gl" :package gl)` expands to
exactly one of these directives per WIT function — same `:from`/`:as`/`:params`/`:returns`
shape, same synthetic-defun mechanism, same `WasmImportInjector` post-pass — so the module
is byte-identical to the hand-written block and `--optimize` still shakes the never-called
imports. `:from` defaults to the interface's bare name; the WIT label becomes the `:as`
field camelCased (`:field-style :camel`, the default) or verbatim (`:kebab`). Only
`:int`/`:float`/`:bool`/`:string` are reachable from a WIT type (nothing maps to
`:s-expr`), so a WIT type outside that flat set is a compile error naming the WIT file and
line — the interpreter/JVM lowering has no such limit, because there a `wit-import` lowers
to a provider call, not to an import. The `gl.lisp` block above is precisely the shape that
crosses (handles, scalars, strings): a hand-written `local:webgl/gl.wit` was measured to
reproduce it byte-for-byte, and all but four of its imports are an exact kebab -> camel
match. The four that are not are places gl.lisp gave the Lisp side DIFFERENT WORDS from the
host field (`shader-compiled-p` / `getShaderParameter`, ...), which a WIT label — one name
serving as both — cannot express; the decision is that the WIT names win, so migrating the
demos means renaming those four call sites (`.todo/132`).

Tests: `WasmImportCompilerTest` (structural: import-section order, index shift,
allocator gating, mode rejection), preload-based E2E in
`WasmLispCompilerIntegrationTest` (`wasmtime run --preload host=... main.wasm`, host
module itself compiled from Lisp with `:as` aliases), stub tests in
`LispEvaluatorTest`/`JvmLispCompilerTest`. Showcases: `examples/browser/webgl-triangle/` (hello
world: 10 imports, no exports, whole program in top-level forms run by `_initialize`;
deliberately self-contained, does not use the shared package),
`examples/browser/webgl-cube/` (3D: mat4 math in Lisp, bulk floats via a `setFloat` staging
array) and `examples/browser/webgl-galaxy/` (browser
host; the whole WebGL pipeline is driven from Lisp through 34 imports -- GLSL sources as
Lisp strings via `:string` params, handle-table one-liner JS bindings, `:string` results
for shader info logs, Math.sin/cos -- staged to Pages via pom.xml); cube, galaxy,
heat3d and robot-arm all pull the WebGL2 boundary from `examples/browser/webgl-common/gl.lisp`.

**The component path does NOT go through this compiler** (todo 128): `rontolisp:wasm-import`
is still a Preview-1-only directive (`--component` throws). A `rontolisp:wit-import` under
`--component` instead lowers to the internal `rontolisp::%component-import` form, which
`WasmComponentImportCompiler` turns into canonical-ABI marshalling defuns — a different
compiler, but the SAME synthetic-defun + `PLACEHOLDER_FUNC_BASE` + `WasmImportInjector`
mechanism described above, sharing one ordinal space with these imports. See `.kb/wit.md`
("Component imports").
