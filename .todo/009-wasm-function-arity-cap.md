# WASM: raise the 7-parameter function limit

**Status:** documented + guarded, not raised. **Update 2026-07-05:** the
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
in the README. The interpreter and JVM backends have no such limit.

## Why not raised now

Raising `MAX_CALLABLE_ARITY` shifts the `TYPE_CALLABLE_BASE + N` indices and
every type index after them (`TYPE_READ_LINE` etc.), plus the dispatch function
count. The `--component` blobs (`import-block*.bin`, adapters) are pinned to
fixed type/function indices, so a naive bump risks breaking component mode. A
safe raise needs the index wiring re-derived and the component blobs re-checked
(see `src/wasm-component/README.md`).

## What to do if raised

- Bump `MAX_CALLABLE_ARITY`, add the extra `callable_arity_N` types and dispatch
  bodies, re-derive the dependent `TYPE_*`/`FUNC_*` constants, and re-run the
  component ABI tests.
- Remove the arity guards in `WasmLispCompiler` (defun + lambda loops) and the
  README note, and add an E2E case exercising an 8+ parameter function.

## Workaround (idiomatic)

Bundle related arguments into a list/plist, which keeps arities small and reads
better than long positional lists. `examples/maze-rl.lisp` does this with its
`(alpha gamma epsilon max-steps)` hyper-parameter list.
