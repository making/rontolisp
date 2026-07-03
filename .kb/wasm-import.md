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
section and `ScalarWasmCompiler`.

Tests: `WasmImportCompilerTest` (structural: import-section order, index shift,
allocator gating, mode rejection), preload-based E2E in
`WasmLispCompilerIntegrationTest` (`wasmtime run --preload host=... main.wasm`, host
module itself compiled from Lisp with `:as` aliases), stub tests in
`LispEvaluatorTest`/`JvmLispCompilerTest`. Showcase: `examples/webgl-galaxy/` (browser
WebGL host; imports `drawParticle` and Math.sin/cos, staged to Pages via pom.xml).
