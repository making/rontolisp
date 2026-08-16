# 421. `logtest` does not exist

Difficulty: Low

`(logtest integer-1 integer-2)` -- the standard CL predicate, defined as
`(not (zerop (logand integer-1 integer-2)))` -- is missing on every backend. A
caller gets `The function <PKG>::LOGTEST is undefined`, because the name is not
in `PackageRegistry.CL_SYMBOLS` either, so it does not even resolve to `cl:`.

Found by the jose spike (`.todo/419`): `trivial-utf-8`'s
`utf-8-bytes-to-string` tests the UTF-8 continuation bits with it, so decoding
ANY token payload back to a string fails on it -- well before the JSON layer.
Defining it by hand in the `trivial-utf-8` package was enough to unblock the
whole spike, which is the measure of how narrow this is.

`logbitp` is already present with a doc page and full four-backend wiring, so it
is the template to copy at every step of the "Adding a New Built-in Function"
checklist in `CLAUDE.md`. `logcount` is missing the same way; it has no consumer
on record, so add it only if the sweep is free.

## Definition of done

`logtest` works on all four backends -- `LispNames` + `PackageRegistry`,
`Environment.createGlobal`, `JvmLogtestCompiler` / `WasmLogtestCompiler` (or the
existing shared integer-bitwise route `logbitp` takes,
`.kb/integer-bitwise-fast-paths.md`), and a `BuiltinFunctionWrappers` entry so
`#'logtest` is a value. Pinned in `LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` plus a `ci-spec.yaml` case, with the doc page
(`reference/functions/logtest.md` en+ja), the `_catalog.yaml` entry and the
`reference/functions.md` row beside `logbitp`.
