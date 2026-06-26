# Hash tables (missing builtins)

**Status:** not implemented. Needed for idiomatic lookup/counting code such as
the deferred word-frequency example
([03-text-analysis-example-blocked](03-text-analysis-example-blocked.md)).

There is currently no hash table type, so frequency counting and keyed lookups
fall back to alists, which (a) are O(n) and (b) cannot key on strings because
`assoc` is `eql`-only (see
[06-sequence-test-key-keywords](06-sequence-test-key-keywords.md)).

## Functions to add

- `make-hash-table` (support at least `:test` of `eql` and `equal`).
- `gethash` (with the two-value/`:default` flavor reduced to a single returned
  value if multiple values are out of scope) and `(setf (gethash k h) v)`.
- `remhash`, `clrhash`, `hash-table-count`, `hash-table-p`.
- `maphash` (iterate key/value), or at least a way to enumerate entries so a
  frequency table can be turned into a sortable list.

## Notes / design

- `(setf (gethash ...) v)` needs a new `setf` place. `setf` expansion lives in
  the shared `LispMacroExpander` (see CLAUDE.md "Adding a New Macro" / the `setf`
  place list). Add a `gethash` place there so all three backends get it at once.
- Backend representations differ: interpreter can use a `java.util.HashMap`;
  JVM-compiled output may reuse the same; the WASM backend has no host map, so
  this likely needs a small linear-probing table in linear memory (the heaviest
  part of this task -- could be deferred to interpreter+JVM first, with WASM
  raising "cannot compile" until implemented, matched in `ci-spec.yaml`).
- Wire-up points are the same as any builtin (CLAUDE.md "Adding a New Built-in
  Function"): `LispNames`/`PackageRegistry`, `Environment`, `Jvm*`/`Wasm*`
  compilers, `BuiltinFunctionWrappers`, README + tests.

## Definition of done

Idiomatic word-frequency counting works on interpreter and JVM (WASM if the
in-memory table is implemented), e.g.:

```lisp
(let ((h (make-hash-table :test 'equal)))
  (dolist (w words) (incf (gethash w h 0)))
  ...)
```
