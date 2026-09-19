# Compile-path adjust-array `:displaced-to` on an `:adjustable` source must keep identity

Difficulty: Medium

Split out of `.todo/905` (2026-09-19), which the interpreter closed but the JVM/WASM
compile paths did not fully cover. The interpreter now turns an `:adjustable` array
adjusted with `:displaced-to` into the displaced view IN PLACE (`eq`, like any
adjustable adjustment). The compile path (`LispMacroExpander.expandAdjustArray`) builds
a FRESH displaced array via `make-array`, so the result is NOT `eq` to the source on the
JVM or the two WASM backends. The contents and the `array-displacement` answer are the
same; only identity differs. Documented in `.kb/adjustable-arrays.md` (adjust-array
section) as a deliberate, unpinned divergence.

## What needs to happen

On the compile path, `adjust-array` with `:displaced-to` on an `:adjustable` source must
return the source itself, mutated in place into the displaced view, matching the
interpreter (and CLHS -- `adjust-array` returns the array when it is adjustable). This is
the equivalent of the interpreter's `LispArray.becomeDisplaced` / `LispString.becomeDisplaced`.

The non-`adjustable` path (fresh displaced array, already working) is correct and must not
change. The `%array-become` primitive copies dims/data/fillPointer but NOT the displaced
target/offset, so it cannot carry the displacement -- a dedicated mechanism is needed.

## Notes

- The interpreter side is DONE and pinned by `adjustArrayInitialContentsAndDisplacedTo`
  / `anAdjustedCopyKeepsTheElementType`; the ANSI row (interpreter-only) exercises the
  in-place identity shape (`.ADJUSTABLE.12/.13`, `.STRING.ADJUSTABLE.11/.13`, ...).
- The result of `adjust-array ... :displaced-to` on an adjustable SOURCE must preserve
  `adjustable-array-p`, the fill pointer and the remembered element type (`.kb`
  "adjust-array never changes an array's element type").
- Wiring: a new compile-time primitive (e.g. `%array-become-displaced`) is likely needed,
  emitted in `JvmArrayRuntimeBuilder` (`_arrayBecomeDisplaced`) and `WasmArrayCompiler`
  (following `compileMakeDisplaced`), registered alongside the existing `%array-become`
  in `LispNames` / `PackageRegistry` / the `programUsesAnyArrayOp` gates /
  `Jvm`/`WasmExprCompiler.compileCons`.
- Cross-backend pinning: add a ci-spec `-cross-backend` case asserting `(eq source
  (adjust-array source n :displaced-to ...))` for an adjustable source, once the compile
  path matches. Until then, DO NOT add that assertion, since the compile paths currently
  answer a fresh array.
- `.kb/adjustable-arrays.md`: update the line that records the compile-path identity
  divergence once fixed.
