# Symbol and package extensions

**Status:** partially implemented. The symbol-function subset (`symbol-name`
/`symbol-value`/`boundp`/`fboundp`/`intern`/`find-symbol`/`make-symbol` + the
earlier `gensym`) shipped 2026-07-05 as ASDF Phase 3 unit 6 — see
`.kb/symbol-runtime-api.md` for the semantics (verbatim names, no intern table,
global-only variable lookups, compile-path folds/gates). The DECLARATIVE package
system shipped too: `defpackage` with `:use`/`:export`/`:nicknames`
/`:import-from`/`:documentation`/`:size` is a top-level directive resolved by
`PackageResolver` (see `.kb/packages.md`). What remains is the RUNTIME
package/symbol reflection API (symbol plists, `symbol-package`,
`macro-function`, `unintern`, and the package-query functions). Medium priority
— needed for larger programs and libraries.

## What's missing

RontoLisp has `in-package`, `*package*`, `defpackage`, and `symbol-function`,
plus nine built-in packages (`cl`, `cl-user`, `rontolisp`, `linalg`, `vec`,
`usocket`, `java`, `asdf`, `ql`) and the built-in nicknames `common-lisp`,
`common-lisp-user`, `rl`, `la`, `quicklisp`. The following symbol and package
operators are absent:

### Missing symbol functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `symbol-plist` | Symbol property list: `(symbol-plist 'foo)` | Easy |
| `symbol-package` | Home package of the symbol | Easy |
| `macro-function` | Get a macro's expansion function | Easy |
| `unintern` | Remove symbol from package | Easy |

`.kb/symbol-runtime-api.md` argues `symbol-plist` and `macro-function` are
feasible as name-keyed side tables — nothing about the no-intern-table design
blocks them.

### Missing package functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `find-package` | Find package by name/string | Easy |
| `package-name` | Package name string | Easy |
| `list-all-packages` | List all packages | Easy |
| `package-use-list` | Packages used by this one | Easy |
| `package-used-by-list` | Packages that use this one | Easy |
| `package-shadowing-symbols` | Shadowed symbols (would always be empty) | Easy |
| `export` | Runtime function form only — the `defpackage` `:export` clause already exists | Medium |
| `import` | Runtime function form only — the `defpackage` `:import-from` clause already exists | Medium |
| ~~`use-package`~~ | DONE (2026-07-31): a read/compile-time directive like `in-package` (`PackageResolver.usePackage`), plus an interpreter-side runtime function for computed calls -- see `.kb/packages.md` | -- |

## Non-goals (by design)

- `copy-symbol` — impossible. `.kb/symbol-runtime-api.md`: true uninterned
  identity is unrepresentable (symbols compare by name, there is no intern
  table), so a "copy with separate values" has nothing to distinguish it from
  the original.
- `shadow` / `shadowing-import` — a documented non-goal. `defpackage`'s
  `:shadow`/`:shadowing-import-from` clauses raise a tailored "rontolisp has no
  symbol shadowing" error (`.kb/packages.md`); the runtime functions would have
  nothing to do.

### Implementation approach

**Symbol functions** (highest ROI):
1. `symbol-plist` — a name-keyed side table (no field on the symbol needed).
2. `macro-function` — expose the macro table the expander already keeps.
3. `symbol-package` — derive from the canonical `pkg::name` spelling.
4. `unintern` — package symbol removal.

**Package functions**:
5. `find-package`, `list-all-packages` — package registry queries.
6. `package-name`, `package-use-list`, `package-used-by-list` — metadata.
7. `export`, `import` — the runtime forms of clauses `defpackage` already
   implements declaratively. (`use-package` shipped 2026-07-31 as a
   read/compile-time directive: the use list is consulted while the resolver
   walks the program, so a purely runtime effect would be invisible to the forms
   it must affect. `export`/`import` have the same constraint and should follow
   the same shape.)

**Compiler considerations**:
- `symbol-value` at compile time vs runtime needs disambiguation.
- Package operations at compile time affect resolution.

### Field finding (2026-08-02, from the todo-232/231 survey)

A runtime `(export (intern "X" "PKG") "PKG")` after `(defun pkg::x ...)` does
not make `pkg:x` callable: a computed `export` never reaches the resolver (only
LITERAL exports fold, `PackageResolver.tryConsumeExport`), and a later `pkg:x`
spelling is rejected at resolve time ("not external"). The RUNTIME designator
route already works — the `_lookup` alias rows serve `PKG:NAME` for every
`PKG::NAME` defun — so the gap is purely the compile-time single-colon
spelling. Hit while trying to patch uiop from Lisp. Fix belongs to the runtime
`export` item above (the read/compile-time directive shape `use-package` took).

### Related

- `[[034-local-function-definition]]` (`macrolet`/`symbol-macrolet`)
- `[[035-type-system]]` (`typep` on `symbol` type)

**Consumer (2026-08-15, rove `.todo/372`)**: `remprop` -- rove's `remove-test`
is `(remprop name 'test)`; today "the function REMPROP is undefined; compiled as
a call-time error". Same name-keyed side table as `get`/`(setf get)`
(`LispPreludeLibrary` `%symbol-plists`), one prelude defun. `macro-function` is
now `.todo/378` (rove needs the compile-path half too).
