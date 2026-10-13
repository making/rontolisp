# f82. Clojure: `set-validator!` of a computed macro var

Difficulty: Small

Left by f72 (2026-10-13): f72 routes a literal `(set-validator! #'mm f)`
below the macro's definition through `%clojure-set-validator-macro`, which
checks the present expander, since the var's site reads no value. A computed
target still reads the signaling site:

| program | clj 1.12.6 | here |
|---|---|---|
| `(defmacro mm [] :m1) (set-validator! (if true #'mm #'mm) (fn [v] (fn? v)))` | `nil` | `Can't take value of a macro` |
| `(defmacro mm [] :m1) ((fn [v] (set-validator! v (fn [v] (fn? v)))) #'mm)` | `nil` | `Can't take value of a macro` |
| `(defmacro mm [] :m1) (apply set-validator! [#'mm (fn [v] (fn? v))])` | `nil` | `Can't take value of a macro` |

The oracle checks the macro function in all three shapes. Here the `-v`
function value and any computed reference go through `%clojure-set-validator`,
which reads the current root through the var's getter, and a macro's getter
signals. Document row: `doc/*/clojure/deviations.md` (the `set-validator!`
of a computed macro var sentence).

## What decides the design

- The expander is only known at lower time for a literal site below the
  definition (f72's `macroValidatorOf`); a computed site would need the
  runtime to map the var back to its expander, but a whole program's table
  holds the expander's index, not the expander.
- The `#'` site's getter must keep signaling: `deref`/`var-get` of a macro's
  var signal by design (documented deviation; the oracle answers the expander).

## Plan

1. clojure-spec row for the table, oracle-identical, four backends.
2. The runtime/lowering shape that checks a computed macro target without
   touching the signaling getter; the `ClojureLibraryTest` pin stays green
   (no-watch programs lower unchanged).
3. Shrink the deviation row in `doc/en` + `doc/ja`.
