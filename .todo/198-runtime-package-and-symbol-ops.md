# Runtime package / symbol operations that postmodern needs

Goal: the small runtime package-and-symbol surface that s-sql and postmodern
actually call, on all backends. This is NOT the full intern-table redesign
(`.todo/156-symbol-model-intern-table-redesign.md`) nor the full extension
catalog (`.todo/038-symbol-and-package-extensions.md`) -- decide per item
whether it can land on the current "symbols compare by name" model
(`.kb/symbol-runtime-api.md`) or genuinely needs 156.

Blocks `.todo/195-s-sql-support.md` (one site) and
`.todo/202-postmodern-non-mop-milestone.md`.

## Call sites and what they need

- `s-sql/s-sql.lisp:243-247`: `(intern (map 'string ...) (find-package
  :keyword))` -- `intern` with a NON-LITERAL package argument (hard error
  today). Minimal: `find-package` returns a designator and `intern` accepts
  any designator that resolves to `:keyword` (and the current package).
- `postmodern/util.lisp`: `(values (intern (string-upcase name) "KEYWORD"))`
  -- STRING package designator for `intern`.
- `postmodern/json-encoder.lisp` (5 sites) and `deftable.lisp:60`: runtime
  `(find-package :simple-date)` / `find-package` probes for OPTIONAL systems
  -- must return `nil` for unknown packages, and `(find-symbol "TIMESTAMP"
  :simple-date)` must return `nil` (not error) when the package is absent;
  the result is then fed to `typep`, so `(typep x nil-designator)` on a nil
  type must behave (upstream guards with `and`, so just `find-symbol`
  tolerance is enough -- verify).
- `postmodern/prepare.lisp`: `find-symbol` + **`fmakunbound`** to delete
  `defprepared`-generated functions. `fmakunbound` on the compiled backends
  collides with eager compilation -- a defensible lite semantics is "the name
  becomes call-time-undefined again"; needs the same late-binding cell the
  2026-07-26 undefined-function work introduced. Also `remhash`/`clrhash`
  (verify these exist).
- `postmodern/deftable.lisp:5,8`: **`(setf documentation)`** on a variable --
  today `documentation` is lite (reads nil, writes discarded); a discarded
  write is fine, but it must not error.
- `postmodern/deftable.lisp` (`create-package-tables`): `symbol-package`
  comparison -- verify `symbol-package` exists and compares usefully under
  the name-based model.
- `postmodern/table.lisp`: `fdefinition` on symbols and `(setf (fdefinition
  ...))`? (verify -- reads only, via column writers), `make-symbol` for
  uninterned working symbols, `functionp`.

## Reader-side sibling (already confirmed working)

Pipe-escaped symbols (`:|a b|`, `\!dao-def`, `\!index` -- postmodern exports
symbol names starting with an escaped `!`) read correctly; keep a pinning
test so they stay working through `PackageResolver` canonicalization
(`.kb/packages.md`), including `:|\||` / `:||` (empty-name keyword) which
s-sql dispatches on.
