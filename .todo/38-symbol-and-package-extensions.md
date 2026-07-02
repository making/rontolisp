# Symbol and package system extensions

**Status:** not implemented. Medium priority — needed for larger programs and libraries.

## What's missing

RontoLisp has a basic package system (`in-package`, `*package*`, three built-in packages: `cl`, `cl-user`, `rontolisp`) and `symbol-function`. The following symbol and package operators are absent:

### Missing symbol functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `symbol-name` | Get symbol name string: `(symbol-name 'foo)` -> `"FOO"` | Easy |
| `symbol-value` | Get/set symbol's variable value: `(symbol-value 'x)` | Easy |
| `symbol-plist` | Symbol property list: `(symbol-plist 'foo)` | Easy |
| `symbol-packages` | Packages that know the symbol | Easy |
| `symbol-package` | Home package of the symbol | Easy |
| `symbol-macrofunction` | Get symbol macro expansion | Medium |
| `macro-function` | Get macro expansion function | Easy |
| `fboundp` | Boundp in function namespace | Easy |
| `boundp` | Boundp in variable namespace | Easy |
| `find-symbol` | Find symbol in package by name | Easy |
| `intern` | Intern symbol in package | Easy |
| `unintern` | Remove symbol from package | Easy |
| `read-from-string` (already done) | Parse form from string | — |
| `gensym` | Generate unique symbol | Easy |
| `copy-symbol` | Copy symbol with separate values | Easy |
| `make-symbol` | Create uninterned symbol | Easy |
| `readtable-p` | Readtable predicate | — |
| `package-symbol-wrapper` | (Not CL) | — |

### Missing package functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `defpackage` | Define package with imports/exports | Hard |
| `package-name` | Package name string | Easy |
| `package-description` | Human-readable description | Easy |
| `package-error` | Signal package error | — |
| `find-package` | Find package by name/string | Easy |
| `list-all-packages` | List all packages | Easy |
| `use-package` | Use another package's symbols | Medium |
| `shadow` | Shadow external symbols | Medium |
| `shadowing-import` | Import with shadow | Medium |
| `import` | Import symbols | Medium |
| `export` | Export symbols | Medium |
| `package-use-list` | Packages used by this one | Easy |
| `package-used-by-list` | Packages that use this one | Easy |
| `package-shadowing-symbols` | Shadowed symbols | Easy |
| `package-inherited-operations` | Inherited symbols | Easy |
| `package-nil-error` | (Not CL) | — |
| `in-package` (already done) | Switch package | — |

### Implementation approach

**Symbol functions** (highest ROI):
1. `symbol-name`, `symbol-value` — direct accessors on `LispSymbol`.
2. `fboundp`, `boundp` — check environment maps.
3. `gensym`, `make-symbol`, `copy-symbol` — symbol creation.
4. `symbol-plist` — add plist field to `LispSymbol` (or a side table).

**Package functions**:
5. `find-symbol`, `intern`, `unintern` — package symbol lookup/creation.
6. `find-package`, `list-all-packages` — package registry queries.
7. `package-name`, `package-description` — metadata.
8. `export`, `import`, `shadow`, `shadowing-import`, `use-package` — package relationship management.
9. `defpackage` — the big one; declarative package definition.

**Compiler considerations**:
- `gensym` must be compile-time (generate unique names for the output).
- `symbol-value` at compile time vs runtime needs disambiguation.
- Package operations at compile time affect resolution.

### Related

- `[[34-local-function-definition]]` (`symbol-macrolet` uses `symbol-macrofunction`)
- `[[35-type-system]]` (`typep` on `symbol` type)
