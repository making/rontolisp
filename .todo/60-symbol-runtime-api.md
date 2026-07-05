# 60: Phase 3 unit 6 -- runtime symbol API (intern / symbol-name / find-symbol ...)

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Wishlist source: `.todo/38-symbol-and-package-extensions.md` (this
unit is the symbol-function subset; package-mutation functions like
`export`/`use-package`/runtime `find-package` stay in 38).

## Scope (v1)

- `symbol-name`: `(symbol-name 'foo)` -> `"foo"` (decide case: rontolisp
  symbols are case-preserving lowercase, unlike CL's upcase -- document the
  deviation; check what `princ-to-string` of a symbol already returns and
  stay consistent).
- `intern`: `(intern "foo")` -> the symbol `foo` (package argument: support
  the current-package/default form first; a package designator argument can
  error until packages exist at runtime).
- `find-symbol`: like intern but never creates; nil when absent.
- `make-symbol`: fresh uninterned symbol (aligns with the existing `#:` /
  gensym machinery).
- `boundp` / `fboundp`: environment lookups.
- `symbol-value`: variable-namespace read by symbol.

## Design caution

The compile path is the hard part: symbols created at RUNTIME did not go
through `PackageResolver`, and `symbol-value`/`fboundp` need a runtime
name->binding table which the compilers only partially have (the `--dynamic`
late-binding machinery and the emitted `eval` runtime already maintain
related structures -- see `.kb/dynamic-late-binding.md` and
`.kb/eval-runtime.md`; reuse, don't duplicate). If full compiled support is
too costly, ship interpreter + JVM first and make WASM a clear compile error,
documented (precedent: tcp/tls are interpreter/JVM-only).

## Wiring checklist

Full built-in-function workflow per name + BuiltinFunctionWrappers;
list-functions expectations + ci-spec introspection; docs (en+ja,
functions/) + catalog; `.kb` note; native E2E.
