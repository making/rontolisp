# 68: `integer-length` + `logbitp`

Split off from `.todo/65-cl-utilities-support.md` (cl-utilities stdlib residue,
step 2). Single-session sized.

## Goal

Two new bit-test builtins, all four backends:

- `(integer-length n)` -- number of bits needed for the two's-complement magnitude
  of `n` (excluding sign). `(integer-length 0)` = 0, `(integer-length 5)` = 3,
  `(integer-length -1)` = 0, `(integer-length -5)` = 3, `(integer-length 255)` = 8.
- `(logbitp index n)` -- t iff bit `index` (0 = LSB) of the two's-complement `n`
  is set. Negative `n` has infinitely many high 1-bits, so `(logbitp k -1)` = t
  for all k.

## Why

cl-utilities `rotate-byte` (non-SBCL branch) uses `integer-length`; `logbitp`
is its natural companion and cheap to add alongside.

## Current state

Neither name exists (`grep integer-length logbitp` is empty). Follow the existing
bit-op precedent: `logand`/`logior`/`logxor`/`lognot`/`ash` are builtins.
- `LispNames.LOGAND`/`ASH` etc. at `LispNames.java` ~line 137-156.
- Interpreter defs live near the other `log*` functions in `Environment.java`.
- JVM/WASM: find the `logand`/`ash` compiler cases (`Jvm*`/`Wasm*` bit-op
  compilers) and mirror them.

## Plan (follow "Adding a New Built-in Function" in CLAUDE.md)

1. `LispNames` + `PackageRegistry.CL_FUNCTIONS` (bump the cl function count; the
   pinned count lives in `LispEvaluatorTest#listFunctionsReturnsSortedClFunctions`
   `hasSize(...)`, JVM x2 + WASM x1 `list-functions` length assertions, and the
   `rontolisp-package-introspection` ci-spec case -- currently 222).
2. Interpreter (`Environment`, `BigInteger.bitLength()` / `testBit()`) ->
   `LispEvaluatorTest`.
3. JVM compiler (`Jvm<Name>Compiler` or inline in the bit-op compiler) ->
   `JvmLispCompilerTest`.
4. WASM compiler -- note WASM integers are i31/i64; `integer-length` on large
   magnitudes and negative `logbitp` need care (the `.todo/46` large-float and
   bignum caveats). If a clean bignum path is impractical on WASM, scope to the
   fixnum range and document the limit (mirror how other WASM numeric builtins
   degrade). -> `WasmLispCompilerIntegrationTest`.
5. `BuiltinFunctionWrappers` (unary `integer-length`, binary `logbitp`).
6. ci-spec case + per-operator docs pages + `_catalog.yaml`.

## Acceptance

The examples above on all four backends (or documented WASM range limit). Native
E2E green.
