# e41. ns-form clauses and require options real libraries use

Difficulty: Low

`ClojureNamespaceLowering` refuses, by name, forms that are routine in library and
application code:

- `(:gen-class)` -> `ns clause :gen-class is not supported yet`. Nearly every app with a
  `-main` carries it; there is no class to generate (`e38` calls `-main` directly). Accept
  and ignore its options, or refuse only the options that change behavior (decide).
- `(:load ...)` clause and the `load` function (relative to the current file).
- `:as-alias` (Clojure 1.11): alias without loading -> `require option :as-alias is not
  supported yet`.
- `:rename` in `:require` and `:refer-clojure` (both refused).

## Plan

1. Read `.kb/clojure-frontend.md`, "Namespaces and project files".
2. Measure each on `clj` 1.12.6; oracle-diffed `clojure-spec.yaml` lines; four backends.
3. `.kb/clojure-frontend.md`; `doc/en` + `doc/ja`.
