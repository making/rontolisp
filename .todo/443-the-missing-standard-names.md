# 443. The missing standard `cl` names

Difficulty: Medium

Child of `.todo/436` (read it first). **Wave 2 -- do this LAST**, see the
collision note below.

## Add

Standard `cl` names upstream ASDF spells that are missing from
`PackageRegistry.CL_SYMBOLS`, or registered there with no implementation:

- `with-compilation-unit` -- a `progn` wrapper is a legitimate implementation
  (there is no `compile-file`), but it has to EXIST
- `*load-verbose*` / `*load-print*` -- currently unbound
- `user-homedir-pathname`
- `do-symbols`, `copy-symbol`
- `invoke-debugger`, `remove-method`
- `file-stream`, `synonym-stream`, `readtable` -- as type names
- `most-positive-fixnum`
- `compile-file` / `compile-file-pathname` -- a `not-implemented` stub is the
  right implementation, shaped so that nothing on any path actually calls it

The five-point recipe is CLAUDE.md's "Adding a New Built-in Function"
(`LispNames` -> `PackageRegistry.CL_SYMBOLS` -> `Environment.createGlobal` ->
the two compilers -> `BuiltinFunctionWrappers`).

## Do NOT add

The runtime package-mutation half of the same diff -- `make-package`,
`delete-package`, `rename-package`, `package-nicknames`, `shadow`,
`shadowing-import`, `unintern`, `unuse-package`. They are a documented non-goal
(`.kb/symbol-runtime-api.md`); read it and raise the question before adding any
of them, rather than adding them because they are on a list.

`(setf (symbol-value v) x)` and `set` belong to `.todo/367`, not here.

## The collision note

This child adds the most names, and every `CL_FUNCTIONS` addition moves the
`list-functions` count that is pinned in `ci-spec.yaml`, `LispEvaluatorTest`,
`JvmLispCompilerTest` (twice) and `WasmLispCompilerIntegrationTest`
(`.kb/packages.md`). Land it after its siblings, and re-count after the last
rebase rather than before.

## Acceptance

Every name resolves and is callable on all four backends; ONE ci-spec case for
the batch (`missing-cl-names-443`), not one per name.
