# WASM: raise the 7-parameter function limit

**Status:** documented + guarded, not raised; **parked, not scheduled**
(re-checked 2026-07-17: the limit and both guards are still live, the stated
blocker was not -- see "Why not raised now"). **Update 2026-07-05:** the
practical pressure is off -- `WasmArityBundler` (a WASM-only AST pre-pass) now
auto-bundles fixed-arity defuns past the limit (keep 6 params + pack the rest
into a list, rewriting all direct call sites), so wide library signatures like
split-sequence's 10-parameter internals compile unchanged. `#'name` of a
bundled function is a clear error; lambdas and `&rest` definitions past the
limit still hard-error. Raising the limit for real remains as below.

The WASM backend supports callable types only for arities 0..`MAX_CALLABLE_ARITY`
(= 7), so a `defun`/`lambda` with more than seven parameters cannot be given a
correct WASM function type. Previously such a function compiled to invalid
bytecode silently (e.g. `TYPE_CALLABLE_BASE + 8` aliased an unrelated type,
giving wasmtime errors like "type mismatch: expected i32, found (ref ...)").

Now `WasmLispCompiler` raises a clear `UnsupportedOperationException` for any
defun/lambda with > 7 parameters ("the WASM backend supports at most 7
parameters ... bundle the extra arguments into a list"), and the limit is noted
in `doc/{en,ja}/compiling/wasm.md`. The interpreter and JVM backends have no
such limit.

## Why not raised now

Not because it is hard -- because nothing needs it. The bundler took the
pressure off (see the Status update above), so the only surface left is an 8+
parameter `lambda`, an 8+ parameter `&rest` defun, and `#'name` of a bundled
function -- each with the same idiomatic workaround as ever (bundle the extra
arguments into a list), and each documented in `doc/{en,ja}/compiling/wasm.md`.

**The original blocker was wrong and no longer applies.** This section used to
claim the `--component` blobs (`import-block*.bin`, the adapters) were pinned to
fixed type/function indices of the core module, so a bump risked breaking
component mode. They are not: the adapters import the core module's functions
**by name** (`(import "w" "stdout-write" ...)` in `adapter.wat`), and the fixed
indices in `WasmComponentBuilder` live in the *component's own* index spaces
(import instances, component funcs, `canon lower`ed core funcs), which the core
module's `TYPE_*`/`FUNC_*` numbering does not feed. The hand-written serve /
fetch / sockets WAT adapters that the claim predated are deleted anyway.

Inside the core module the wiring is already derivation-based, not hand-pinned:
`TYPE_READ_LINE` = `TYPE_CALLABLE_BASE + MAX_CALLABLE_ARITY + 1` and
`FUNC_PLIST_GET` = `FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1` shift on their
own, the `callable_arity_N` types are emitted in a loop over the constant, and
`WasmEvalRuntimeBuilder`'s `_apply` arity ladder loops over it too. So a bump is
close to mechanical; it just buys nothing today.

## What to do if raised

- Bump `MAX_CALLABLE_ARITY`. The `callable_arity_N` types, the dispatch bodies
  and the dependent `TYPE_*`/`FUNC_*` constants follow from it; verify that
  claim rather than assume it, since it is what makes this cheap.
- Re-run the WASM + component E2E on every blob variant. The blobs do not read
  these indices, but the module they wrap grows a type and a function, so the
  variants are what proves the derivation held.
- Remove the arity guards in `WasmLispCompiler` (defun + lambda loops), drop
  `WasmArityBundler` if the new cap makes it dead, update the limit notes in
  `doc/{en,ja}/compiling/wasm.md` (incl. the `eval` note in
  `guides/eval-limitations.md`), and add an E2E case exercising an 8+ parameter
  function.

## Workaround (idiomatic)

Bundle related arguments into a list/plist, which keeps arities small and reads
better than long positional lists. `examples/ml/maze-rl.lisp` does this with its
`(alpha gamma epsilon max-steps)` hyper-parameter list.
